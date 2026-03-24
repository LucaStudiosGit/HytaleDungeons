package com.LucaStudios.HytaleDungeons.Movement;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.VelocityConfig;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.navigation.AStarNode;
import com.hypixel.hytale.server.npc.navigation.AStarWithTarget;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ClickToMoveHandler {

    private static final double ARRIVAL_RADIUS_SQUARED = 0.25;
    private static final long TICK_INTERVAL_MS = 50L;

    private final AStarWithTarget pathfinder = new AStarWithTarget();
    private final ConcurrentHashMap<UUID, PlayerMoveState> activeMovements = new ConcurrentHashMap<>();
    private final ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "click-to-move-tick");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> tickTask;
    private JavaPlugin plugin;

    public void register(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        tickTask = tickExecutor.scheduleAtFixedRate(
                this::tickAllMovements, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel(false);
        tickExecutor.shutdown();
        activeMovements.clear();
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        if (event.getMouseButton().mouseButtonType != MouseButtonType.Left) return;
        if (event.getMouseButton().state != MouseButtonState.Released) return;

        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock == null) return;

        PlayerRef playerRef = event.getPlayerRefComponent();
        if (playerRef == null || !playerRef.isValid()) return;

        EntityStore entityStore = (EntityStore) playerRef.getReference().getStore().getExternalData();
        World world = entityStore.getWorld();

        Vector3d target = new Vector3d(targetBlock.x + 0.5, targetBlock.y, targetBlock.z + 0.5);

        activeMovements.compute(playerRef.getUuid(), (uuid, existing) -> {
            PlayerMoveState state = (existing != null) ? existing : new PlayerMoveState(playerRef, world, plugin);
            state.setDestination(pathfinder, target);
            return state;
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        activeMovements.remove(event.getPlayerRef().getUuid());
    }

    private void tickAllMovements() {
        activeMovements.entrySet().removeIf(entry -> {
            try {
                return entry.getValue().scheduleStep();
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING)
                        .withCause(e)
                        .log("Click-to-move tick failed for player %s", entry.getKey());
                return true;
            }
        });
    }

    private static final class PlayerMoveState {

        private final PlayerRef playerRef;
        private final World world;
        private final JavaPlugin plugin;
        private final Deque<Vector3d> waypoints = new ArrayDeque<>();
        private volatile boolean done = false;

        PlayerMoveState(PlayerRef playerRef, World world, JavaPlugin plugin) {
            this.playerRef = playerRef;
            this.world = world;
            this.plugin = plugin;
        }

        void setDestination(AStarWithTarget pathfinder, Vector3d target) {
            waypoints.clear();
            done = false;

            AStarNode node = pathfinder.getPath();
            while (node != null) {
                waypoints.add(new Vector3d(node.getPosition()));
                node = node.getNextPathNode();
            }

            if (waypoints.isEmpty() || !target.equals(waypoints.peekLast())) {
                waypoints.add(target);
            }

            plugin.getLogger().at(Level.INFO).log(
                    "setDestination: target=%s waypoints=%d", target, waypoints.size()
            );
        }

        boolean scheduleStep() {
            if (done || waypoints.isEmpty() || !playerRef.isValid()) return true;

            world.execute(() -> {
                try {
                    step();
                } catch (Exception e) {
                    plugin.getLogger().at(Level.WARNING)
                            .withCause(e)
                            .log("Click-to-move step failed for player %s", playerRef.getUuid());
                    done = true;
                }
            });
            return false;
        }

        private void step() {
            if (waypoints.isEmpty() || !playerRef.isValid()) {
                done = true;
                stopMovement();
                return;
            }

            Vector3d waypoint = waypoints.peek();
            if (waypoint == null) {
                done = true;
                stopMovement();
                return;
            }

            Vector3d current = playerRef.getTransform().getPosition();
            double dx = waypoint.x - current.x;
            double dz = waypoint.z - current.z;
            double distSq = dx * dx + dz * dz;

            if (distSq <= ARRIVAL_RADIUS_SQUARED) {
                waypoints.poll();
                if (waypoints.isEmpty()) {
                    done = true;
                    stopMovement();
                }
                return;
            }

            MovementManager movementManager = playerRef.getComponent(MovementManager.getComponentType());
            float speed = 4;//(movementManager != null) ? movementManager.getSettings().baseSpeed : 3.0f;
//            plugin.getLogger().at(Level.INFO).log(
//                    "Moving player at speed %s", speed
//            );
            double dist = Math.sqrt(distSq);
            float vx = (float) ((dx / dist) * speed);
            float vz = (float) ((dz / dist) * speed);

            sendVelocity(vx, 0f, vz);
            playAnimation("Run");
        }

        private void stopMovement() {
            sendVelocity(0f, 0f, 0f);
            stopAnimation();
        }

        private void sendVelocity(float vx, float vy, float vz) {
            VelocityConfig config = new VelocityConfig(1.0f, 1.0f, 1.0f, 1.0f, 0.01f, VelocityThresholdStyle.Linear);
            playerRef.getPacketHandler().writeNoCache(
                    new ChangeVelocity(vx, vy, vz, ChangeVelocityType.Set, config)
            );
        }

        private void playAnimation(String name) {
            var entityRef = playerRef.getReference();
            AnimationUtils.playAnimation(entityRef, AnimationSlot.Movement, name, true, entityRef.getStore());
        }

        private void stopAnimation() {
            var entityRef = playerRef.getReference();
            AnimationUtils.stopAnimation(entityRef, AnimationSlot.Movement, true, entityRef.getStore());
        }
    }
}
