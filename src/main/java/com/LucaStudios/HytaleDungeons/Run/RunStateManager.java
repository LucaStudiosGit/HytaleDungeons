package com.LucaStudios.HytaleDungeons.Run;

import com.LucaStudios.HytaleDungeons.Camera.TopDownView;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorData;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Central coordinator for dungeon run lifecycle.
 * Manages per-player state transitions and notifies other systems.
 *
 * @see RunState
 * @see RunData
 */
public final class RunStateManager {

    // --- Tuning Knobs (from GDD) ---
    public static final int MAX_LIVES = 3;
    public static final int STARTING_FLOOR = 1;
    public static final long DEATH_SCREEN_DURATION_MS = 3000L;
    public static final long DESCENDING_TRANSITION_DURATION_MS = 2000L;

    // --- Default Loadout (from GDD) ---
    private static final String DEFAULT_WEAPON = "Weapon_Sword_Iron";
    private static final String DEFAULT_CROSSBOW = "Weapon_Crossbow_Iron";

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, RunData> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "run-state-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** Optional listener for state changes — other systems subscribe here. */
    private Consumer<StateChangeEvent> stateChangeListener;

    private HealthManager healthManager;
    private PlayerDataManager playerDataManager;
    private FloorGenerator floorGenerator;

    public RunStateManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Set the HealthManager for HP reset coordination.
     * Must be called before register().
     */
    public void setHealthManager(HealthManager healthManager) {
        this.healthManager = healthManager;
    }

    /**
     * Set the PlayerDataManager for gear/XP/level tracking.
     * Must be called before register().
     */
    public void setPlayerDataManager(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    /**
     * Set the FloorGenerator for dungeon layout creation.
     * Must be called before register().
     */
    public void setFloorGenerator(FloorGenerator floorGenerator) {
        this.floorGenerator = floorGenerator;
    }

    /**
     * Register event listeners with the plugin.
     */
    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    /**
     * Clean up resources on plugin shutdown.
     */
    public void shutdown() {
        scheduler.shutdown();
        runs.clear();
    }

    /**
     * Set a listener that is notified on every state transition.
     */
    public void setStateChangeListener(Consumer<StateChangeEvent> listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Get the run data for a player, or null if not in a run.
     */
    public RunData getRunData(UUID playerId) {
        return runs.get(playerId);
    }

    // ---- State Transitions ----

    /**
     * Called by the health/combat system when the player dies.
     */
    public void onPlayerDeath(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) {
            return;
        }

        RunState oldState = data.getState();
        data.setState(RunState.DEAD);
        fireStateChange(playerId, oldState, RunState.DEAD, data);

        log("Player %s died on floor %d (%d lives left)", playerId, data.getCurrentFloor(), data.getLivesRemaining());

        // After death screen duration, resolve: respawn or game over
        scheduler.schedule(() -> resolveDeathScreen(playerId, playerRef), DEATH_SCREEN_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Called by the enemy/floor system when a mob is killed.
     */
    public void onMobKilled(UUID playerId) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) {
            return;
        }
        data.decrementMobs();
        log("Mob killed for player %s — %d remaining", playerId, data.getMobsRemaining());
    }

    /**
     * Called when the player enters the exit zone.
     * Floor completion requires mobsRemaining == 0.
     */
    public void onPlayerReachedExit(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) {
            return;
        }
        if (data.getMobsRemaining() > 0) {
            // Can't exit yet — mobs still alive
            return;
        }

        RunState oldState = data.getState();
        data.setState(RunState.UPGRADING);
        fireStateChange(playerId, oldState, RunState.UPGRADING, data);

        log("Player %s completed floor %d — entering upgrade selection", playerId, data.getCurrentFloor());

        // TODO: Open upgrade selection UI via Floor Upgrade Selection system
    }

    /**
     * Called by the upgrade selection system when the player picks an upgrade.
     */
    public void onUpgradeSelected(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.UPGRADING) {
            return;
        }

        RunState oldState = data.getState();
        data.setState(RunState.DESCENDING);
        data.setCurrentFloor(data.getCurrentFloor() + 1);
        fireStateChange(playerId, oldState, RunState.DESCENDING, data);

        log("Player %s descending to floor %d", playerId, data.getCurrentFloor());

        // Generate the next floor — callback fires when ready
        if (floorGenerator != null) {
            World world = worldFromPlayerRef(playerRef);
            if (world != null) {
                //floorGenerator.generateFloor(playerId, data.getCurrentFloor(), world, playerRef, () -> activateNextFloor(playerId, playerRef));
            } else {
                //floorGenerator.generateFloor(playerId, data.getCurrentFloor(), () -> activateNextFloor(playerId, playerRef));
            }
        } else {
            // Fallback: timer-based transition if no floor generator
            scheduler.schedule(() -> activateNextFloor(playerId, playerRef), DESCENDING_TRANSITION_DURATION_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Called from GAME_OVER when the player chooses "New Run".
     */
    public void onNewRunRequested(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.GAME_OVER) {
            return;
        }

        RunState oldState = data.getState();
        data.reset(MAX_LIVES, STARTING_FLOOR);
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        // Reset HP to full on new run
        if (healthManager != null) {
            healthManager.resetHealth(playerId);
        }

        // Reset player data (gear, backpack, XP, level)
        if (playerDataManager != null) {
            playerDataManager.resetPlayer(playerId);
        }

        log("Player %s starting new run", playerId);

        // Re-equip default loadout and generate floor 1
        equipDefaultLoadout(playerRef);
        if (floorGenerator != null) {
            World world = worldFromPlayerRef(playerRef);
            floorGenerator.generateFloor(playerId, STARTING_FLOOR, world, playerRef, () -> {
                FloorData floor = floorGenerator.getActiveFloor(playerId);
                if (floor != null) {
                    data.setMobsRemaining(floor.getMobSpawnCount());
                }
            });
        }
    }

    /**
     * Set the initial mob count for the current floor.
     * Called by Floor Generation after a floor is created.
     */
    public void setMobCount(UUID playerId, int count) {
        RunData data = runs.get(playerId);
        if (data != null) {
            data.setMobsRemaining(count);
        }
    }

    // ---- Internal ----

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();

        world.execute(() -> {
            if (!entityRef.isValid()) {
                return;
            }

            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }

            UUID playerId = playerRef.getUuid();

            // Set up camera
            TopDownView.enable(playerRef);

            // Create run data — player starts in FLOOR_ACTIVE on floor 1
            RunData data = new RunData(playerId, MAX_LIVES, STARTING_FLOOR);
            runs.put(playerId, data);

            // Equip default loadout
            equipDefaultLoadout(playerRef);

            // Initialize health tracking
            if (healthManager != null) {
                healthManager.initPlayer(playerId);
            }

            // Initialize player data (equipped gear, backpack, XP, level)
            if (playerDataManager != null) {
                playerDataManager.initPlayer(playerId);
            }

            fireStateChange(playerId, null, RunState.FLOOR_ACTIVE, data);
            log("Player %s joined — starting run on floor %d with %d lives", playerId, STARTING_FLOOR, MAX_LIVES);

            // Generate floor 1 with block placement and teleport
            if (floorGenerator != null) {
                floorGenerator.generateFloor(playerId, STARTING_FLOOR, world, playerRef, () -> {
                    FloorData floor = floorGenerator.getActiveFloor(playerId);
                    if (floor != null) {
                        data.setMobsRemaining(floor.getMobSpawnCount());
                    }
                });
            }
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();
        RunData removed = runs.remove(playerId);
        if (removed != null) {
            if (healthManager != null) {
                healthManager.removePlayer(playerId);
            }
            if (playerDataManager != null) {
                playerDataManager.removePlayer(playerId);
            }
            if (floorGenerator != null) {
                floorGenerator.removePlayer(playerId);
            }
            log("Player %s disconnected — run data cleaned up", playerId);
        }
    }

    private void resolveDeathScreen(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.DEAD) {
            return;
        }

        if (data.getLivesRemaining() > 0) {
            // Respawn on same floor
            data.decrementLives();
            RunState oldState = data.getState();
            data.setState(RunState.FLOOR_ACTIVE);
            fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

            // Reset HP to full on respawn
            if (healthManager != null) {
                healthManager.resetHealth(playerId);
            }

            log("Player %s respawned on floor %d (%d lives left)", playerId, data.getCurrentFloor(), data.getLivesRemaining());

            // Reset floor mobs to original positions
            if (floorGenerator != null) {
                int mobCount = floorGenerator.resetMobs(playerId);
                if (mobCount >= 0) {
                    data.setMobsRemaining(mobCount);
                }
            }
            // TODO: Teleport player to floor spawn
            // TODO: Re-snap camera: TopDownView.enable(playerRef)
        } else {
            // Game over
            RunState oldState = data.getState();
            data.setState(RunState.GAME_OVER);
            fireStateChange(playerId, oldState, RunState.GAME_OVER, data);

            log("Player %s game over on floor %d", playerId, data.getCurrentFloor());

            // TODO: Show game over UI with score screen
        }
    }

    private void activateNextFloor(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.DESCENDING) {
            return;
        }

        RunState oldState = data.getState();
        data.setState(RunState.FLOOR_ACTIVE);
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        // Reset HP to full on new floor
        if (healthManager != null) {
            healthManager.resetHealth(playerId);
        }

        // Set mob count from floor data
        if (floorGenerator != null) {
            FloorData floor = floorGenerator.getActiveFloor(playerId);
            if (floor != null) {
                data.setMobsRemaining(floor.getMobSpawnCount());
            }
        }

        log("Player %s now on floor %d", playerId, data.getCurrentFloor());

        // Re-snap camera after teleport
        // TODO: TopDownView.enable(playerRef) after teleport
        // TODO: Teleport player to new floor spawn
    }

    private void equipDefaultLoadout(PlayerRef playerRef) {
        if (!playerRef.isValid()) {
            return;
        }
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }
        Store<EntityStore> store = entityRef.getStore();

        world(store).execute(() -> {
            if (!entityRef.isValid()) {
                return;
            }
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) {
                return;
            }

            var hotbar = player.getInventory().getHotbar();
            hotbar.setItemStackForSlot((short) 0, new ItemStack(DEFAULT_WEAPON, 1));
            hotbar.setItemStackForSlot((short) 1, new ItemStack(DEFAULT_CROSSBOW, 1));
            // Slot 2 (armor) intentionally left empty — no default armor per GDD
        });
    }

    private static World world(Store<EntityStore> store) {
        return store.getExternalData().getWorld();
    }

    private static World worldFromPlayerRef(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return null;
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getExternalData().getWorld();
    }

    private void fireStateChange(UUID playerId, RunState oldState, RunState newState, RunData data) {
        Consumer<StateChangeEvent> listener = this.stateChangeListener;
        if (listener != null) {
            listener.accept(new StateChangeEvent(playerId, oldState, newState, data));
        }
    }

    private void log(String format, Object... args) {
        plugin.getLogger().at(Level.INFO).log(String.format(format, args));
    }

    /**
     * Immutable snapshot of a state transition, for other systems to react to.
     */
    public record StateChangeEvent(UUID playerId, RunState oldState, RunState newState, RunData runData) {}
}
