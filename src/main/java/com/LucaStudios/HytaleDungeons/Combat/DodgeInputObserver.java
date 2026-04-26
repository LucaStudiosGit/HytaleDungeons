package com.LucaStudios.HytaleDungeons.Combat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Detects Space-key (jump) presses by scanning the player's
 * {@link PlayerInput#getMovementUpdateQueue()} for {@link PlayerInput.SetMovementStates}
 * entries with {@code jumping = true}, and reads the most recent
 * {@link PlayerInput.SetClientVelocity} as the dodge direction.
 *
 * <p>{@link com.LucaStudios.HytaleDungeons.Restrictions.NoJumpRestriction} zeroes
 * {@code jumpForce}, which prevents the engine from ever setting
 * {@code MovementStatesComponent.jumping = true}. So we observe the raw input
 * queue (before {@code ProcessPlayerInput} consumes it) instead.</p>
 *
 * <p>Repeated frames of a held key are debounced by {@link DodgeManager}'s
 * cooldown.</p>
 */
public final class DodgeInputObserver {

    private static final long TICK_MS = 50L; // 20 Hz

    private static final class State {
        final PlayerRef playerRef;
        final Ref<EntityStore> entityRef;
        final Store<EntityStore> store;
        final World world;

        State(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store, World world) {
            this.playerRef = playerRef;
            this.entityRef = entityRef;
            this.store = store;
            this.world = world;
        }
    }

    private final ConcurrentHashMap<UUID, State> tracked = new ConcurrentHashMap<>();
    private final DodgeManager dodgeManager;

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dodge-input-observer");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> tickTask;

    public DodgeInputObserver(DodgeManager dodgeManager) {
        this.dodgeManager = dodgeManager;
    }

    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onReady);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);
        tickTask = ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void onReady(PlayerReadyEvent event) {
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!entityRef.isValid()) return;
            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) return;
            tracked.put(playerRef.getUuid(), new State(playerRef, entityRef, store, world));
        });
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();
        tracked.remove(playerId);
        dodgeManager.removePlayer(playerId);
    }

    private void tick() {
        for (State state : tracked.values()) {
            state.world.execute(() -> tickState(state));
        }
    }

    private void tickState(State state) {
        if (!state.playerRef.isValid() || !state.entityRef.isValid()) return;

        PlayerInput input = state.store.getComponent(state.entityRef, PlayerInput.getComponentType());
        if (input == null) return;

        boolean jumpPressed = false;
        double dirX = 0.0;
        double dirZ = 0.0;
        // The most recent SetClientVelocity in the queue is the client's
        // current WASD-driven intent. Last write wins — keep overwriting
        // as we walk the queue.
        for (PlayerInput.InputUpdate update : input.getMovementUpdateQueue()) {
            if (update instanceof PlayerInput.SetMovementStates sms
                    && sms.movementStates().jumping) {
                jumpPressed = true;
            } else if (update instanceof PlayerInput.SetClientVelocity scv) {
                dirX = scv.getVelocity().getX();
                dirZ = scv.getVelocity().getZ();
            }
        }

        if (jumpPressed) {
            dodgeManager.attemptDodge(state.playerRef, state.entityRef, state.store, dirX, dirZ);
        }
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel(false);
        ticker.shutdownNow();
    }
}
