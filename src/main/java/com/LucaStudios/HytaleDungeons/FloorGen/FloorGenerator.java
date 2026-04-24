package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.math.vector.Vector3d;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class FloorGenerator {

    public static final int FLOOR_Y = 200;
    public static final int FLOOR_X_SPACING = 200;

    // Teleports show up as large single-poll Z jumps. Any delta above this
    // is treated as a teleport and skipped so it doesn't false-fire the trigger.
    private static final double TELEPORT_DELTA_THRESHOLD = 4.0;

    // Re-arm margin above fallY before the watcher will fire again — prevents
    // repeat fires while the teleport-to-spawn is still being processed.
    private static final double FALL_REARM_MARGIN = 5.0;

    private final ConcurrentHashMap<UUID, FloorData> activeFloors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> playerIndices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ZPlaneWatch> zPlaneWatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FallWatch> fallWatches = new ConcurrentHashMap<>();
    private BiConsumer<UUID, PlayerRef> onPlayerFell;
    private int nextPlayerIndex = 0;

    private final Consumer<String> logger;
    private final FloorPlacer placer;
    private final EnemyManager enemyManager;

    public FloorGenerator(EnemyManager enemyManager, Consumer<String> logger) {
        this.logger = logger;
        this.placer = new FloorPlacer(logger);
        this.enemyManager = enemyManager;
        ScheduledExecutorService triggerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "floor-trigger-watcher");
            t.setDaemon(true);
            return t;
        });
        triggerScheduler.scheduleAtFixedRate(this::pollZPlaneTriggers, 200, 200, TimeUnit.MILLISECONDS);
        triggerScheduler.scheduleAtFixedRate(this::pollFallTriggers, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void setOnPlayerFell(BiConsumer<UUID, PlayerRef> callback) {
        this.onPlayerFell = callback;
    }

    private static final class ZPlaneWatch {
        final PlayerRef playerRef;
        final World world;
        final int zPlane;
        Double prevZ;
        boolean fired;

        ZPlaneWatch(PlayerRef playerRef, World world, int zPlane) {
            this.playerRef = playerRef;
            this.world = world;
            this.zPlane = zPlane;
        }
    }

    private void pollZPlaneTriggers() {
        for (var entry : zPlaneWatches.entrySet()) {
            UUID playerId = entry.getKey();
            ZPlaneWatch watch = entry.getValue();
            if (watch.fired || watch.world == null || watch.playerRef == null) continue;
            watch.world.execute(() -> checkZPlaneCrossing(playerId, watch));
        }
    }

    private void checkZPlaneCrossing(UUID playerId, ZPlaneWatch watch) {
        if (watch.fired) return;
        if (!watch.playerRef.isValid()) {
            zPlaneWatches.remove(playerId);
            return;
        }
        var transform = watch.playerRef.getTransform();
        Vector3d pos = transform.getPosition();
        double currZ = pos.z;
        if (watch.prevZ == null) {
            watch.prevZ = currZ;
            return;
        }
        double prev = watch.prevZ;
        double plane = watch.zPlane;
        double low = plane - 0.5;
        double high = plane + 0.5;
        if (Math.abs(currZ - prev) > TELEPORT_DELTA_THRESHOLD) {
            watch.prevZ = currZ;
            return;
        }
        boolean inBand = currZ >= low && currZ <= high;
        boolean jumpedOver = (prev < low && currZ > high) || (prev > high && currZ < low);
        boolean crossed = inBand || jumpedOver;
        watch.prevZ = currZ;
        if (crossed) {
            watch.fired = true;
            onZPlaneTriggerFired(playerId);
        }
    }

    private static final class FallWatch {
        final PlayerRef playerRef;
        final World world;
        final int fallY;
        boolean active = true;

        FallWatch(PlayerRef playerRef, World world, int fallY) {
            this.playerRef = playerRef;
            this.world = world;
            this.fallY = fallY;
        }
    }

    private void pollFallTriggers() {
        for (var entry : fallWatches.entrySet()) {
            UUID playerId = entry.getKey();
            FallWatch watch = entry.getValue();
            if (watch.world == null || watch.playerRef == null) continue;
            watch.world.execute(() -> checkFall(playerId, watch));
        }
    }

    private void checkFall(UUID playerId, FallWatch watch) {
        if (!watch.playerRef.isValid()) {
            fallWatches.remove(playerId);
            return;
        }
        // Mobs sharing this player's floor die the moment they drop below
        // fallY — run this every poll so mobs knocked off the edge don't
        // linger forever in enemyStateMap (and don't block floor-clear).
        enemyManager.killFallenMobs(playerId, watch.fallY);

        double y = watch.playerRef.getTransform().getPosition().y;
        if (!watch.active) {
            if (y > watch.fallY + FALL_REARM_MARGIN) {
                watch.active = true;
            }
            return;
        }
        if (y < watch.fallY) {
            watch.active = false;
            logger.accept(String.format(
                    "Player %s fell below fallY=%d (y=%.1f)", playerId, watch.fallY, y));
            BiConsumer<UUID, PlayerRef> cb = onPlayerFell;
            if (cb != null) {
                cb.accept(playerId, watch.playerRef);
            }
        }
    }

    private void onZPlaneTriggerFired(UUID playerId) {
        FloorData floor = activeFloors.get(playerId);
        if (floor == null) return;
        ZPlaneWatch watch = zPlaneWatches.get(playerId);
        if (watch == null) return;
        SpawnGroup group = floor.findFirstZoneGroup();
        if (group == null) {
            logger.accept("zPlane trigger fired but no zone spawn group on floor " + floor.floorNumber());
            return;
        }
        logger.accept("zPlane trigger fired for floor " + floor.floorNumber()
                + " — spawning group " + group.id());
        enemyManager.spawnGroup(watch.playerRef, watch.world, group);
    }

    // ── Public API ──────────────────────────────────────────────────────

    public void generateFloor(UUID playerId, int floorNumber, World world, PlayerRef playerRef, Runnable onReady) {
        playerIndices.putIfAbsent(playerId, nextPlayerIndex++);
        int playerIndex = playerIndices.get(playerId);
        int originX = playerIndex * FLOOR_X_SPACING;

        FloorData floor = buildFloor(floorNumber);
        activeFloors.put(playerId, floor);

        SpawnGroup zoneGroup = floor.findFirstZoneGroup();
        if (zoneGroup != null && zoneGroup.zPlane() != 0 && playerRef != null) {
            zPlaneWatches.put(playerId, new ZPlaneWatch(playerRef, world, zoneGroup.zPlane()));
        } else {
            zPlaneWatches.remove(playerId);
        }

        if (playerRef != null && world != null) {
            fallWatches.put(playerId, new FallWatch(playerRef, world, floor.fallY()));
        } else {
            fallWatches.remove(playerId);
        }

        logger.accept("Floor " + floor.floorNumber() + " built for " + playerId);

        if (world != null) {
            world.execute(() -> {
                int preRemoved = enemyManager.removeTrackedMobs();
                enemyManager.cleanAllEntities();
                logger.accept("Mob cleanup: pre-removed " + preRemoved + " tracked mobs");

                enemyManager.registerFloor(playerId, playerRef, world, floor.spawnGroups());

                if (playerRef != null) {
                    float[] spawnPoint = floor.playerSpawnPoint();
                    placer.teleportPlayer(playerRef, spawnPoint[0], spawnPoint[1], spawnPoint[2]);
                }

                if (onReady != null) {
                    onReady.run();
                }
            });
        } else {
            enemyManager.cleanAllEntities();
            enemyManager.registerFloor(playerId, playerRef, world, floor.spawnGroups());
            if (onReady != null) {
                onReady.run();
            }
        }
    }

    public void removePlayer(UUID playerId) {
        playerIndices.remove(playerId);
        zPlaneWatches.remove(playerId);
        fallWatches.remove(playerId);
    }

    public FloorData getActiveFloor(UUID playerId) {
        return activeFloors.get(playerId);
    }

    public void teleportToActiveFloorSpawn(UUID playerId, PlayerRef playerRef) {
        FloorData floor = activeFloors.get(playerId);
        if (floor == null || playerRef == null || !playerRef.isValid()) return;
        float[] sp = floor.playerSpawnPoint();
        if (sp == null || sp.length < 3) return;
        placer.teleportPlayer(playerRef, sp[0], sp[1], sp[2]);
    }

    // ── Generation Logic ────────────────────────────────────────────────

    FloorData buildFloor(int floorNumber) {
        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        FloorTemplate template = library.getTemplate(floorNumber);
        return new FloorData(
                floorNumber,
                new float[]{template.spawnPointX(), template.spawnPointY(), template.spawnPointZ()},
                template.fallY(),
                template.spawnGroups());
    }
}
