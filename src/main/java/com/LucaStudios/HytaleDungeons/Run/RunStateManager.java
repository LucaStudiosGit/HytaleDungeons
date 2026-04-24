package com.LucaStudios.HytaleDungeons.Run;

import com.LucaStudios.HytaleDungeons.Camera.TopDownView;
import com.LucaStudios.HytaleDungeons.Inventroy.Equipment;
import com.LucaStudios.HytaleDungeons.Loot.ItemCategory;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerLoadout;
import com.LucaStudios.HytaleDungeons.Visual.FullBright;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorData;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorTemplateLibrary;
import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
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
    // NOTE: Hytale's vanilla Weapon_Sword_Wood ships with NO BaseDamage interactions,
    // so it deals zero damage and never fires a damage event. Crude is the
    // closest "starter" feel that has full damage interactions defined.
    private static final String DEFAULT_WEAPON = "Weapon_Sword_Crude";
    private static final String DEFAULT_CROSSBOW = "Weapon_Crossbow_Iron";
    private static final String DEFAULT_ARROW = "Weapon_Arrow_Crude";

    /** Hytale armor slot ordinals — see {@code com.hypixel.hytale.protocol.ItemArmorSlot}. */
    private static final short ARMOR_SLOT_CHEST = 1;

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
    private com.LucaStudios.HytaleDungeons.UI.DeathPage deathPage;
    private com.LucaStudios.HytaleDungeons.UI.GameOverPage gameOverPage;
    private com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage betweenFloorsPage;
    private com.LucaStudios.HytaleDungeons.UI.VictoryPage victoryPage;

    /** Optional: modal death page shown during the death-screen duration. */
    public void setDeathPage(com.LucaStudios.HytaleDungeons.UI.DeathPage deathPage) {
        this.deathPage = deathPage;
    }

    /** Optional: modal game-over page shown when the player runs out of lives. */
    public void setGameOverPage(com.LucaStudios.HytaleDungeons.UI.GameOverPage gameOverPage) {
        this.gameOverPage = gameOverPage;
    }

    /** Optional: modal between-floors page shown after a floor is cleared. */
    public void setBetweenFloorsPage(com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage betweenFloorsPage) {
        this.betweenFloorsPage = betweenFloorsPage;
    }

    /** Optional: modal victory page shown after the final floor is cleared. */
    public void setVictoryPage(com.LucaStudios.HytaleDungeons.UI.VictoryPage victoryPage) {
        this.victoryPage = victoryPage;
    }

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
        data.incrementDeaths();
        fireStateChange(playerId, oldState, RunState.DEAD, data);

        log("Player %s died on floor %d (%d lives left)", playerId, data.getCurrentFloor(), data.getLivesRemaining());

        // Final death — skip the death screen and resolve straight to game over
        // (resolveDeathScreen opens the GameOverPage on the no-lives branch).
        if (data.getLivesRemaining() <= 0) {
            resolveDeathScreen(playerId, playerRef);
            return;
        }

        // Show our custom death page (native one suppressed in PlayerDeathObserver).
        if (deathPage != null && playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                try {
                    deathPage.showFor(playerRef, store, DEATH_SCREEN_DURATION_MS,
                            Math.max(0, data.getLivesRemaining() - 1));
                } catch (Throwable t) {
                    plugin.getLogger().at(Level.WARNING).log(
                            "DeathPage.showFor failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }

        // After death screen duration, resolve: respawn or game over
        scheduler.schedule(() -> resolveDeathScreen(playerId, playerRef), DEATH_SCREEN_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Called by the enemy/floor system when a mob is killed. When the last
     * mob on the floor dies, automatically advance to the next floor.
     */
    public void onMobKilled(UUID playerId) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) {
            return;
        }
        data.incrementMobsKilled();
        data.decrementMobs();
        log("Mob killed for player %s — %d remaining", playerId, data.getMobsRemaining());

        if (data.getMobsRemaining() <= 0) {
            showBetweenFloorsScreen(playerId, data);
        }
    }

    /**
     * Transition into the between-floors page. Sets state to {@code UPGRADING}
     * (movement/combat disabled) and opens the modal. The page's "Next Level"
     * button calls {@link #onNextFloorRequested} to resume the run.
     */
    private void showBetweenFloorsScreen(UUID playerId, RunData data) {
        PlayerRef playerRef = data.getPlayerRef();

        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        int nextFloor = data.getCurrentFloor() + 1;
        if (library == null || nextFloor > library.floorCount()) {
            showVictoryScreen(playerId, data);
            return;
        }

        RunState oldState = data.getState();
        data.setState(RunState.UPGRADING);
        fireStateChange(playerId, oldState, RunState.UPGRADING, data);

        log("Player %s cleared floor %d — showing between-floors screen",
                playerId, data.getCurrentFloor());

        if (betweenFloorsPage != null && playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                final int clearedFloor = data.getCurrentFloor();
                final int revives = data.getLivesRemaining();
                try {
                    betweenFloorsPage.showFor(playerRef, store, clearedFloor, revives);
                } catch (Throwable t) {
                    plugin.getLogger().at(Level.WARNING).log(
                            "BetweenFloorsPage.showFor failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
    }

    /**
     * Called by {@link com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage} when
     * the player picks one of the three offered items. Replaces the equipped
     * slot matching the item's category in both {@link PlayerLoadout} and the
     * Hytale hotbar, then kicks off the advance-floor pipeline.
     */
    public void onOfferSelected(UUID playerId, PlayerRef playerRef, String itemId, int itemLevel) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.UPGRADING) {
            return;
        }

        ItemDefinition item = ItemDatabase.getInstance().get(itemId);
        if (playerDataManager != null) {
            playerDataManager.replaceEquippedForCategory(playerId, itemId, itemLevel);
        }

        // Mirror the pick onto the Hytale hotbar on the world thread.
        if (playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                World world = store.getExternalData().getWorld();
                world.execute(() -> writeEquippedItemToHotbar(entityRef, store, item));
            }
        }

        log("Player %s picked offer %s LVL %d (%s) — advancing floor",
                playerId, itemId, itemLevel, item.getCategory());

        advanceFloor(playerId, data);
    }

    /**
     * Writes a single equipped item into the right native inventory slot.
     * Weapons go to hotbar 0, crossbows to hotbar 1, armor to the Chest slot
     * of {@link InventoryComponent.Armor} (slot 1 — Head=0, Chest=1, Hands=2,
     * Legs=3 per {@code com.hypixel.hytale.protocol.ItemArmorSlot}).
     */
    private void writeEquippedItemToHotbar(Ref<EntityStore> entityRef,
                                           Store<EntityStore> store,
                                           ItemDefinition item) {
        if (!entityRef.isValid() || item == null || item.getHytaleItemId().isEmpty()) {
            return;
        }
        ItemStack stack = new ItemStack(item.getHytaleItemId(), 1);

        if (item.getCategory() == ItemCategory.ARMOR) {
            var armor = store.getComponent(entityRef, InventoryComponent.Armor.getComponentType());
            if (armor == null) return;
            armor.getInventory().setItemStackForSlot(ARMOR_SLOT_CHEST, stack);
            return;
        }

        var hotbar = store.getComponent(entityRef, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) return;
        short slot = switch (item.getCategory()) {
            case WEAPON -> (short) 0;
            case CROSSBOW -> (short) 1;
            case ARMOR -> (short) 0; // unreachable — handled above
        };
        hotbar.getInventory().setItemStackForSlot(slot, stack);
    }

    /**
     * Transition a player to the next floor: bump {@code currentFloor}, set
     * state to {@code DESCENDING}, regen HP, and kick off floor generation.
     * When generation finishes the state lands back in {@code FLOOR_ACTIVE}.
     * If there is no next floor, logs a win and leaves the run on the
     * current floor (TODO: proper win screen).
     */
    private void advanceFloor(UUID playerId, RunData data) {
        PlayerRef playerRef = data.getPlayerRef();

        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        int nextFloor = data.getCurrentFloor() + 1;
        if (library == null || nextFloor > library.floorCount()) {
            showVictoryScreen(playerId, data);
            return;
        }

        RunState oldState = data.getState();
        data.setCurrentFloor(nextFloor);
        data.setState(RunState.DESCENDING);
        fireStateChange(playerId, oldState, RunState.DESCENDING, data);

        log("Player %s advancing to floor %d", playerId, nextFloor);

        // Refill HP before the next floor.
        if (healthManager != null) {
            healthManager.resetHealth(playerId);
        }

        // Regenerate the floor (despawns stale mobs, rebuilds geometry,
        // teleports to new spawn, re-arms zone triggers). When ready, flip
        // state to FLOOR_ACTIVE and seed mob count from the generated floor.
        if (floorGenerator != null && playerRef != null && playerRef.isValid()) {
            World world = worldFromPlayerRef(playerRef);
            floorGenerator.generateFloor(playerId, nextFloor, world, playerRef, () -> {
                RunState descending = data.getState();
                data.setState(RunState.FLOOR_ACTIVE);
                fireStateChange(playerId, descending, RunState.FLOOR_ACTIVE, data);
                FloorData floor = floorGenerator.getActiveFloor(playerId);
                if (floor != null) {
                    data.setMobsRemaining(floor.getMobSpawnCount());
                }
                TopDownView.enable(playerRef);
            });
        }
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
     * Transition to the victory screen — the final floor has just been cleared.
     * Sets state to {@code VICTORY} (movement/combat disabled) and opens the
     * modal page seeded with a snapshot of run stats.
     */
    private void showVictoryScreen(UUID playerId, RunData data) {
        RunState oldState = data.getState();
        data.setState(RunState.VICTORY);
        fireStateChange(playerId, oldState, RunState.VICTORY, data);

        log("Player %s cleared the final floor %d — dungeon complete!",
                playerId, data.getCurrentFloor());

        PlayerRef playerRef = data.getPlayerRef();
        if (victoryPage == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }
        Store<EntityStore> store = entityRef.getStore();

        int playerLevel = (playerDataManager != null)
                ? playerDataManager.getPlayerLevel(playerId) : 1;
        var stats = new com.LucaStudios.HytaleDungeons.UI.VictoryPage.VictoryStats(
                data.getCurrentFloor(),
                data.getTotalMobsKilled(),
                data.getTotalDeaths(),
                data.getLivesRemaining(),
                playerLevel,
                data.getRunDurationMs());

        World world = store.getExternalData().getWorld();
        final com.LucaStudios.HytaleDungeons.UI.VictoryPage pageRef = victoryPage;
        world.execute(() -> {
            try {
                pageRef.showFor(playerRef, store, stats);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).log(
                        "VictoryPage.showFor failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    /**
     * Called from GAME_OVER or VICTORY when the player chooses "New Run".
     */
    public void onNewRunRequested(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null
                || (data.getState() != RunState.GAME_OVER
                        && data.getState() != RunState.VICTORY)) {
            return;
        }

        RunState oldState = data.getState();
        data.reset(MAX_LIVES, STARTING_FLOOR);
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        // Clear the native death component so mobs treat the player as alive.
        if (playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                try {
                    store.removeComponent(entityRef, DeathComponent.getComponentType());
                } catch (Throwable t) {
                    log("onNewRunRequested: failed to remove DeathComponent for %s: %s",
                            playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }

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

            // Enable fullbright — attaches max-brightness DynamicLight to the player
            FullBright.apply(playerRef);

            // Set up camera
            TopDownView.enable(playerRef);

            // Create run data — player starts in LOBBY until they press Start.
            RunData data = new RunData(playerId, MAX_LIVES, STARTING_FLOOR);
            data.setPlayerRef(playerRef);
            data.setState(RunState.LOBBY);
            runs.put(playerId, data);

            // Initialize health tracking — mirrors our MAX_HP onto the native HP bar
            if (healthManager != null) {
                healthManager.initPlayer(playerId, playerRef, entityRef, store);
            }

            // Initialize player data (equipped gear, backpack, XP, level)
            if (playerDataManager != null) {
                playerDataManager.initPlayer(playerId);
            }

            fireStateChange(playerId, null, RunState.LOBBY, data);
            log("Player %s joined — entering lobby", playerId);
        });
    }

    /**
     * Start a fresh run from the lobby. Invoked by
     * {@link com.LucaStudios.HytaleDungeons.UI.MainMenuPage} when the player
     * presses <b>Start</b>. Equips the default loadout and generates floor 1.
     *
     * <p>Unlike {@code onPlayerReady} (which used to race Hytale's native
     * spawn-point logic and needed a 750ms safety re-teleport), the player
     * has been in the world for seconds while browsing the lobby, so the
     * native spawn logic has already settled. A single teleport is enough.</p>
     */
    public void startRunFromLobby(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.LOBBY) {
            return;
        }

        // Reset run stats so timer/kill count start at zero for this run.
        data.reset(MAX_LIVES, STARTING_FLOOR);
        RunState oldState = RunState.LOBBY;
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        log("Player %s starting run from lobby", playerId);

        equipDefaultLoadout(playerRef);

        if (floorGenerator == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        World world = worldFromPlayerRef(playerRef);
        floorGenerator.generateFloor(playerId, STARTING_FLOOR, world, playerRef, () -> {
            FloorData floor = floorGenerator.getActiveFloor(playerId);
            if (floor != null) {
                data.setMobsRemaining(floor.getMobSpawnCount());
            }
        });
    }

    /**
     * Return to the lobby from a terminal run state (GAME_OVER or VICTORY).
     * Invoked by the "Back to Lobby" button on those pages. For GAME_OVER we
     * additionally clear the native death component and refill HP (the player
     * ended the run dead); for VICTORY the player is already alive and the
     * ECS entity is in a delicate post-transition state, so we skip those
     * mutations entirely. Either way the run ends up sitting in
     * {@link RunState#LOBBY} so the main menu's prepare step can teleport
     * the player to the configured lobby spawn.
     */
    public void onReturnToLobby(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null
                || (data.getState() != RunState.GAME_OVER
                        && data.getState() != RunState.VICTORY)) {
            return;
        }

        boolean wasGameOver = data.getState() == RunState.GAME_OVER;
        RunState oldState = data.getState();
        data.reset(MAX_LIVES, STARTING_FLOOR);
        data.setState(RunState.LOBBY);
        fireStateChange(playerId, oldState, RunState.LOBBY, data);

        if (wasGameOver) {
            // Clear the native death component and refill HP — the player
            // ended the run dead so these are both needed before lobby entry.
            if (playerRef != null && playerRef.isValid()) {
                Ref<EntityStore> entityRef = playerRef.getReference();
                if (entityRef != null && entityRef.isValid()) {
                    Store<EntityStore> store = entityRef.getStore();
                    try {
                        store.removeComponent(entityRef, DeathComponent.getComponentType());
                    } catch (Throwable t) {
                        log("onReturnToLobby: failed to remove DeathComponent for %s: %s",
                                playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }
            if (healthManager != null) {
                try {
                    healthManager.resetHealth(playerId);
                } catch (Throwable t) {
                    log("onReturnToLobby: resetHealth failed for %s: %s",
                            playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }

        if (playerDataManager != null) {
            try {
                playerDataManager.resetPlayer(playerId);
            } catch (Throwable t) {
                log("onReturnToLobby: resetPlayer failed for %s: %s",
                        playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        log("Player %s returned to lobby (from %s)", playerId, oldState);
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

        // Re-resolve the PlayerRef from Universe — the ref we captured at
        // death time is stale because the native respawn pipeline moves the
        // entity between archetype chunks in the 3s death-screen window.
        PlayerRef freshRef = resolveFreshPlayerRef(playerId);
        if (freshRef != null) {
            playerRef = freshRef;
            data.setPlayerRef(freshRef);
        }

        if (data.getLivesRemaining() > 0) {
            // Respawn on same floor
            data.decrementLives();
            RunState oldState = data.getState();
            data.setState(RunState.FLOOR_ACTIVE);
            fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

            log("Player %s respawned on floor %d (%d lives left)", playerId, data.getCurrentFloor(), data.getLivesRemaining());

            // Revive on the world thread: clear DeathComponent, refill HP,
            // regenerate the floor (same as !regen), and re-snap the top-down camera.
            World world = worldFromPlayerRef(playerRef);
            if (world != null) {
                final PlayerRef reviveRef = playerRef;
                world.execute(() -> revivePlayer(playerId, reviveRef));
            }
        } else {
            // Game over
            RunState oldState = data.getState();
            data.setState(RunState.GAME_OVER);
            fireStateChange(playerId, oldState, RunState.GAME_OVER, data);

            log("Player %s game over on floor %d", playerId, data.getCurrentFloor());

            if (gameOverPage != null && playerRef != null && playerRef.isValid()) {
                Ref<EntityStore> entityRef = playerRef.getReference();
                if (entityRef != null && entityRef.isValid()) {
                    Store<EntityStore> store = entityRef.getStore();
                    World world = entityRef.getStore().getExternalData().getWorld();
                    var stats = new com.LucaStudios.HytaleDungeons.UI.GameOverPage.GameOverStats(
                            data.getCurrentFloor(),
                            data.getTotalMobsKilled(),
                            data.getLivesRemaining(),
                            data.getRunDurationMs());
                    final com.LucaStudios.HytaleDungeons.UI.GameOverPage pageRef = gameOverPage;
                    world.execute(() -> {
                        try {
                            pageRef.showFor(playerRef, store, stats);
                        } catch (Throwable t) {
                            plugin.getLogger().at(Level.WARNING).log(
                                    "GameOverPage.showFor failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    });
                }
            }
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

    /**
     * World-thread revival: clear the native {@link DeathComponent}, refill HP
     * via {@link HealthManager#resetHealth}, regenerate the current floor (same
     * behavior as {@code !regen}), and re-enable the top-down camera.
     */
    private void revivePlayer(UUID playerId, PlayerRef playerRef) {
        revivePlayer(playerId, playerRef, 0);
    }

    private static final int MAX_REVIVE_RETRIES = 3;
    private static final long REVIVE_RETRY_DELAY_MS = 100L;

    private void revivePlayer(UUID playerId, PlayerRef playerRef, int attempt) {
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        // The native death flow may still be moving the player between
        // archetypes for a tick or two after death — isValid() returns true
        // but ECS reads/writes blow up with IndexOutOfBoundsException deep
        // inside ArchetypeChunk. If that happens, back off and retry on a
        // later tick so the archetype settles before we touch it.
        if (!isEntitySettled(entityRef, store)) {
            if (attempt < MAX_REVIVE_RETRIES) {
                scheduler.schedule(() -> {
                    World world = worldFromPlayerRef(playerRef);
                    if (world != null) {
                        world.execute(() -> revivePlayer(playerId, playerRef, attempt + 1));
                    }
                }, REVIVE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            log("revivePlayer: entity still not settled after %d attempts — proceeding best-effort for %s",
                    MAX_REVIVE_RETRIES, playerId);
        }

        // Clear the native death component so the player is "alive" again.
        // The native flow may have already removed it during archetype
        // transitions, so a "not in archetype" failure here is benign.
        try {
            store.removeComponent(entityRef, DeathComponent.getComponentType());
        } catch (Throwable t) {
            log("revivePlayer: failed to remove DeathComponent for %s: %s",
                    playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Refill HP (writes native HEALTH stat back to MAX_HP).
        if (healthManager != null) {
            try {
                healthManager.resetHealth(playerId);
            } catch (Throwable t) {
                log("revivePlayer: resetHealth failed for %s: %s",
                        playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        // Re-snap the top-down camera.
        try {
            TopDownView.enable(playerRef);
        } catch (Throwable t) {
            log("revivePlayer: TopDownView.enable failed for %s: %s",
                    playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Full floor regen — despawn old mobs, rebuild geometry, re-spawn mobs,
        // teleport to floor spawn. Mirrors what DebugCommands !regen does.
        if (floorGenerator != null) {
            RunData data = runs.get(playerId);
            int floor = (data != null) ? data.getCurrentFloor() : STARTING_FLOOR;
            World world = store.getExternalData().getWorld();
            try {
                floorGenerator.generateFloor(playerId, floor, world, playerRef, () -> {
                    FloorData floorData = floorGenerator.getActiveFloor(playerId);
                    if (floorData != null && data != null) {
                        data.setMobsRemaining(floorData.getMobSpawnCount());
                    }
                });
            } catch (Throwable t) {
                log("revivePlayer: generateFloor failed for %s: %s",
                        playerId, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Probe whether an entity can safely accept ECS reads/writes right now.
     * Returns false when the entity is mid archetype-transition — isValid()
     * still returns true in that window, but store operations throw
     * IndexOutOfBoundsException inside ArchetypeChunk.
     */
    private boolean isEntitySettled(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        if (entityRef == null || !entityRef.isValid() || store == null) return false;
        try {
            // Any cheap read that walks the archetype chunk will throw if the
            // entity's slot is not yet consistent.
            store.getComponent(entityRef, PlayerRef.getComponentType());
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            var hotbar = store.getComponent(entityRef, InventoryComponent.Hotbar.getComponentType());
            var storage = store.getComponent(entityRef, InventoryComponent.Storage.getComponentType());
            var backpack = store.getComponent(entityRef, InventoryComponent.Backpack.getComponentType());
            var armor = store.getComponent(entityRef, InventoryComponent.Armor.getComponentType());
            if (hotbar == null) return;

            hotbar.getInventory().clear();
            if (storage != null) storage.getInventory().clear();
            if (backpack != null) backpack.getInventory().clear();
            if (armor != null) armor.getInventory().clear();

            hotbar.getInventory().setItemStackForSlot((short) 0, new ItemStack(DEFAULT_WEAPON, 1));
            hotbar.getInventory().setItemStackForSlot((short) 1, new ItemStack(DEFAULT_CROSSBOW, 1));

            // Give 30 arrows in storage (not hotbar — hotbar is locked to slot 0)
            if (storage != null) {
                storage.getInventory()
                        .setItemStackForSlot((short) 0, new ItemStack(DEFAULT_ARROW, 30));
            }
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
