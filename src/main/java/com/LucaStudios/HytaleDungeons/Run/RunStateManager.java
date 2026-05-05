package com.LucaStudios.HytaleDungeons.Run;

import com.LucaStudios.HytaleDungeons.Camera.TopDownView;
import com.LucaStudios.HytaleDungeons.Inventroy.Equipment;
import com.LucaStudios.HytaleDungeons.Loot.ItemCategory;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.Party.PartyManager;
import com.LucaStudios.HytaleDungeons.Party.PartyRunData;
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
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    public static final long DOWNED_DURATION_MS = 30_000L;

    // --- Default Loadout (from GDD) ---
    private static final String DEFAULT_WEAPON = "Weapon_Sword_Crude";
    private static final String DEFAULT_CROSSBOW = "Weapon_Crossbow_Iron";
    private static final String DEFAULT_ARROW = "Weapon_Arrow_Crude";

    private static final short ARMOR_SLOT_CHEST = 1;

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, RunData> runs = new ConcurrentHashMap<>();
    // Keyed by partyId (only present for actual multi-player parties)
    private final ConcurrentHashMap<UUID, PartyRunData> partyRuns = new ConcurrentHashMap<>();
    // Downed timers for party death mechanic, keyed by playerId
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> downedTimers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "run-state-scheduler");
        t.setDaemon(true);
        return t;
    });

    private Consumer<StateChangeEvent> stateChangeListener;

    private HealthManager healthManager;
    private PlayerDataManager playerDataManager;
    private FloorGenerator floorGenerator;
    private PartyManager partyManager;
    private com.LucaStudios.HytaleDungeons.UI.DeathPage deathPage;
    private com.LucaStudios.HytaleDungeons.UI.GameOverPage gameOverPage;
    private com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage betweenFloorsPage;
    private com.LucaStudios.HytaleDungeons.UI.VictoryPage victoryPage;

    public void setDeathPage(com.LucaStudios.HytaleDungeons.UI.DeathPage deathPage) { this.deathPage = deathPage; }
    public void setGameOverPage(com.LucaStudios.HytaleDungeons.UI.GameOverPage gameOverPage) { this.gameOverPage = gameOverPage; }
    public void setBetweenFloorsPage(com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage betweenFloorsPage) { this.betweenFloorsPage = betweenFloorsPage; }
    public void setVictoryPage(com.LucaStudios.HytaleDungeons.UI.VictoryPage victoryPage) { this.victoryPage = victoryPage; }

    public RunStateManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setHealthManager(HealthManager healthManager) { this.healthManager = healthManager; }
    public void setPlayerDataManager(PlayerDataManager playerDataManager) { this.playerDataManager = playerDataManager; }
    public void setFloorGenerator(FloorGenerator floorGenerator) { this.floorGenerator = floorGenerator; }
    public void setPartyManager(PartyManager partyManager) { this.partyManager = partyManager; }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    public void shutdown() {
        scheduler.shutdown();
        runs.clear();
        partyRuns.clear();
    }

    public void setStateChangeListener(Consumer<StateChangeEvent> listener) {
        this.stateChangeListener = listener;
    }

    public RunData getRunData(UUID playerId) {
        return runs.get(playerId);
    }

    // ---- Mob kill routing ----

    /**
     * Called by EnemyManager when a mob dies. The id is the partyId for party runs
     * (or playerId for solo runs, where partyId == playerId).
     */
    public void onMobKilled(UUID partyOrPlayerId) {
        PartyRunData partyData = partyRuns.get(partyOrPlayerId);
        if (partyData != null) {
            onPartyMobKilled(partyOrPlayerId, partyData);
        } else {
            // Solo run: id == playerId
            onSoloMobKilled(partyOrPlayerId);
        }
    }

    private void onSoloMobKilled(UUID playerId) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) return;
        data.incrementMobsKilled();
        data.decrementMobs();
        if (data.getMobsRemaining() <= 0) {
            showBetweenFloorsScreen(playerId, data);
        }
    }

    private void onPartyMobKilled(UUID partyId, PartyRunData partyData) {
        if (partyData.getState() != RunState.FLOOR_ACTIVE) return;
        partyData.incrementMobsKilled();
        int remaining = partyData.decrementAndGetMobs();
        // Also increment each member's individual kill stat
        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d != null) d.incrementMobsKilled();
        }
        if (remaining <= 0) {
            showPartyBetweenFloorsScreen(partyId, partyData);
        }
    }

    // ---- Death routing ----

    public void onPlayerDeath(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.FLOOR_ACTIVE) return;

        // Check if this is a party run
        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);

        if (partyData != null) {
            onPartyPlayerDeath(playerId, playerRef, partyId, partyData, data);
        } else {
            onSoloPlayerDeath(playerId, playerRef, data);
        }
    }

    private void onSoloPlayerDeath(UUID playerId, PlayerRef playerRef, RunData data) {
        RunState oldState = data.getState();
        data.setState(RunState.DEAD);
        data.incrementDeaths();
        fireStateChange(playerId, oldState, RunState.DEAD, data);

        if (data.getLivesRemaining() <= 0) {
            resolveDeathScreen(playerId, playerRef);
            return;
        }

        showDeathPage(playerId, playerRef, DEATH_SCREEN_DURATION_MS, Math.max(0, data.getLivesRemaining() - 1));
        scheduler.schedule(() -> resolveDeathScreen(playerId, playerRef), DEATH_SCREEN_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    // ---- Party death / downed mechanic ----

    private void onPartyPlayerDeath(UUID playerId, PlayerRef playerRef,
                                    UUID partyId, PartyRunData partyData, RunData data) {
        if (partyData.isPlayerDowned(playerId)) return; // already downed

        RunState oldState = data.getState();
        data.setState(RunState.DEAD);
        data.incrementDeaths();
        fireStateChange(playerId, oldState, RunState.DEAD, data);

        partyData.setPlayerDowned(playerId, true);

        // If another member is also downed → immediate party wipe
        if (partyData.downedCount() >= 2) {
            cancelAllDownedTimers(partyData);
            scheduler.schedule(() -> resolvePartyWipe(partyId, partyData), 0, TimeUnit.MILLISECONDS);
            return;
        }

        // Show 30s downed screen
        showDeathPage(playerId, playerRef, DOWNED_DURATION_MS, partyData.getSharedRevivesRemaining());

        // Schedule auto-revive after 30s
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (!partyData.isPlayerDowned(playerId)) return; // already handled
            if (partyData.downedCount() >= 2) {
                // Another player also went down before timer fired
                cancelAllDownedTimers(partyData);
                resolvePartyWipe(partyId, partyData);
                return;
            }
            // Solo downed — auto-revive for free
            partyData.setPlayerDowned(playerId, false);
            partyData.cancelDownedTimer(playerId);
            World world = worldFromPlayerRef(playerRef);
            if (world != null) {
                world.execute(() -> revivePartyMember(playerId, playerRef, partyId, partyData));
            }
        }, DOWNED_DURATION_MS, TimeUnit.MILLISECONDS);

        partyData.putDownedTimer(playerId, timer);
        downedTimers.put(playerId, timer);
    }

    private void cancelAllDownedTimers(PartyRunData partyData) {
        for (UUID memberId : partyData.getMembers()) {
            downedTimers.remove(memberId);
        }
        partyData.cancelAllDownedTimers();
    }

    private void resolvePartyWipe(UUID partyId, PartyRunData partyData) {
        // Clear all downed states
        for (UUID memberId : partyData.getMembers()) {
            partyData.setPlayerDowned(memberId, false);
        }

        partyData.decrementRevives();
        log("Party %s wiped — revives remaining: %d", partyId, partyData.getSharedRevivesRemaining());

        if (partyData.getSharedRevivesRemaining() > 0) {
            // Revive all party members and regenerate the floor
            Map<UUID, PlayerRef> memberRefs = collectMemberRefs(partyData.getMembers());
            World world = firstWorld(memberRefs);
            if (world == null) return;

            int floor = partyData.getCurrentFloor();
            int partySize = partyData.getMembers().size();

            // Reset all members to FLOOR_ACTIVE and refill HP
            for (UUID memberId : partyData.getMembers()) {
                RunData d = runs.get(memberId);
                if (d != null) {
                    RunState old = d.getState();
                    d.setState(RunState.FLOOR_ACTIVE);
                    fireStateChange(memberId, old, RunState.FLOOR_ACTIVE, d);
                }
                if (healthManager != null) healthManager.resetHealth(memberId);
            }

            if (floorGenerator != null) {
                floorGenerator.generatePartyFloor(partyId, floor, world, memberRefs, partySize, () -> {
                    FloorData floorData = floorGenerator.getActiveFloor(partyId);
                    if (floorData != null) {
                        partyData.setSharedMobsRemaining(floorData.getMobSpawnCount());
                    }
                    // Re-enable cameras
                    for (PlayerRef pr : memberRefs.values()) {
                        if (pr != null && pr.isValid()) TopDownView.enable(pr);
                    }
                    // Remove DeathComponent for all downed members so Hytale doesn't teleport them
                    for (UUID memberId : partyData.getMembers()) {
                        PlayerRef pr = memberRefs.get(memberId);
                        if (pr == null || !pr.isValid()) continue;
                        Ref<EntityStore> ref = pr.getReference();
                        if (ref == null || !ref.isValid()) continue;
                        try {
                            ref.getStore().tryRemoveComponent(ref, DeathComponent.getComponentType());
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } else {
            onPartyGameOver(partyId, partyData);
        }
    }

    private void revivePartyMember(UUID playerId, PlayerRef playerRef,
                                   UUID partyId, PartyRunData partyData) {
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        try {
            store.tryRemoveComponent(entityRef, DeathComponent.getComponentType());
        } catch (Throwable ignored) {}

        if (healthManager != null) healthManager.resetHealth(playerId);

        RunData data = runs.get(playerId);
        if (data != null) {
            RunState old = data.getState();
            data.setState(RunState.FLOOR_ACTIVE);
            fireStateChange(playerId, old, RunState.FLOOR_ACTIVE, data);
        }

        TopDownView.enable(playerRef);

        // Teleport back to floor spawn
        if (floorGenerator != null) {
            floorGenerator.teleportToActiveFloorSpawn(partyId, playerRef);
        }
    }

    // ---- Between floors ----

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

        if (betweenFloorsPage != null && playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                final int clearedFloor = data.getCurrentFloor();
                final int revives = data.getLivesRemaining();
                World world = store.getExternalData().getWorld();
                final com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage pageRef = betweenFloorsPage;
                world.execute(() -> {
                    try {
                        pageRef.showFor(playerRef, store, clearedFloor, revives);
                    } catch (Throwable t) {
                        plugin.getLogger().at(Level.WARNING).withCause(t).log("BetweenFloorsPage.showFor failed");
                    }
                });
            }
        }
    }

    private void showPartyBetweenFloorsScreen(UUID partyId, PartyRunData partyData) {
        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        int nextFloor = partyData.getCurrentFloor() + 1;
        if (library == null || nextFloor > library.floorCount()) {
            showPartyVictoryScreen(partyId, partyData);
            return;
        }

        // Set all members to UPGRADING
        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            RunState old = d.getState();
            d.setState(RunState.UPGRADING);
            fireStateChange(memberId, old, RunState.UPGRADING, d);
        }
        partyData.setState(RunState.UPGRADING);

        if (betweenFloorsPage == null) return;
        final int clearedFloor = partyData.getCurrentFloor();
        final int revives = partyData.getSharedRevivesRemaining();

        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            PlayerRef playerRef = d.getPlayerRef();
            if (playerRef == null || !playerRef.isValid()) continue;
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) continue;
            Store<EntityStore> store = entityRef.getStore();
            World world = store.getExternalData().getWorld();
            final com.LucaStudios.HytaleDungeons.UI.BetweenFloorsPage pageRef = betweenFloorsPage;
            final PlayerRef pr = playerRef;
            final Store<EntityStore> s = store;
            world.execute(() -> {
                try {
                    pageRef.showFor(pr, s, clearedFloor, revives);
                } catch (Throwable t) {
                    plugin.getLogger().at(Level.WARNING).withCause(t).log("BetweenFloorsPage party showFor failed");
                }
            });
        }
    }

    // ---- Offer selection ----

    public void onOfferSelected(UUID playerId, PlayerRef playerRef, String itemId, int itemLevel) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.UPGRADING) return;

        ItemDefinition item = ItemDatabase.getInstance().get(itemId);
        if (playerDataManager != null) {
            playerDataManager.replaceEquippedForCategory(playerId, itemId, itemLevel);
        }

        if (playerRef != null && playerRef.isValid()) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef != null && entityRef.isValid()) {
                Store<EntityStore> store = entityRef.getStore();
                World world = store.getExternalData().getWorld();
                world.execute(() -> writeEquippedItemToHotbar(entityRef, store, item));
            }
        }

        log("Player %s picked offer %s LVL %d (%s)", playerId, itemId, itemLevel, item.getCategory());

        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);

        if (partyData != null) {
            // Party: advance only when all members have picked
            if (partyData.markOfferPicked(playerId)) {
                partyData.clearOfferPicks();
                advancePartyFloor(partyId, partyData);
            }
        } else {
            advanceFloor(playerId, data);
        }
    }

    private void writeEquippedItemToHotbar(Ref<EntityStore> entityRef,
                                           Store<EntityStore> store,
                                           ItemDefinition item) {
        if (!entityRef.isValid() || item == null || item.getHytaleItemId().isEmpty()) return;
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
            case ARMOR -> (short) 0;
        };
        hotbar.getInventory().setItemStackForSlot(slot, stack);
    }

    // ---- Floor advance ----

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
        if (healthManager != null) healthManager.resetHealth(playerId);

        if (floorGenerator != null && playerRef != null && playerRef.isValid()) {
            World world = worldFromPlayerRef(playerRef);
            floorGenerator.generateFloor(playerId, nextFloor, world, playerRef, () -> {
                RunState descending = data.getState();
                data.setState(RunState.FLOOR_ACTIVE);
                fireStateChange(playerId, descending, RunState.FLOOR_ACTIVE, data);
                FloorData floor = floorGenerator.getActiveFloor(playerId);
                if (floor != null) data.setMobsRemaining(floor.getMobSpawnCount());
                TopDownView.enable(playerRef);
            });
        }
    }

    private void advancePartyFloor(UUID partyId, PartyRunData partyData) {
        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        int nextFloor = partyData.getCurrentFloor() + 1;
        if (library == null || nextFloor > library.floorCount()) {
            showPartyVictoryScreen(partyId, partyData);
            return;
        }

        partyData.setCurrentFloor(nextFloor);
        partyData.setState(RunState.DESCENDING);

        Map<UUID, PlayerRef> memberRefs = collectMemberRefs(partyData.getMembers());
        World world = firstWorld(memberRefs);

        // Transition all members to DESCENDING
        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            RunState old = d.getState();
            d.setCurrentFloor(nextFloor);
            d.setState(RunState.DESCENDING);
            fireStateChange(memberId, old, RunState.DESCENDING, d);
            if (healthManager != null) healthManager.resetHealth(memberId);
        }

        log("Party %s advancing to floor %d", partyId, nextFloor);

        if (floorGenerator != null && world != null) {
            int partySize = partyData.getMembers().size();
            floorGenerator.generatePartyFloor(partyId, nextFloor, world, memberRefs, partySize, () -> {
                partyData.setState(RunState.FLOOR_ACTIVE);
                FloorData floor = floorGenerator.getActiveFloor(partyId);
                if (floor != null) partyData.setSharedMobsRemaining(floor.getMobSpawnCount());

                for (UUID memberId : partyData.getMembers()) {
                    RunData d = runs.get(memberId);
                    if (d == null) continue;
                    RunState old = d.getState();
                    d.setState(RunState.FLOOR_ACTIVE);
                    fireStateChange(memberId, old, RunState.FLOOR_ACTIVE, d);
                    PlayerRef pr = d.getPlayerRef();
                    if (pr != null && pr.isValid()) TopDownView.enable(pr);
                }
            });
        }
    }

    // ---- Victory ----

    private void showVictoryScreen(UUID playerId, RunData data) {
        RunState oldState = data.getState();
        data.setState(RunState.VICTORY);
        fireStateChange(playerId, oldState, RunState.VICTORY, data);

        log("Player %s cleared the final floor %d — dungeon complete!", playerId, data.getCurrentFloor());

        PlayerRef playerRef = data.getPlayerRef();
        if (victoryPage == null || playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        int playerLevel = (playerDataManager != null) ? playerDataManager.getPlayerLevel(playerId) : 1;
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

    private void showPartyVictoryScreen(UUID partyId, PartyRunData partyData) {
        if (victoryPage == null) return;

        partyData.setState(RunState.VICTORY);
        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            RunState old = d.getState();
            d.setState(RunState.VICTORY);
            fireStateChange(memberId, old, RunState.VICTORY, d);
        }

        log("Party %s cleared the final floor — dungeon complete!", partyId);

        int playerLevel = 1;
        for (UUID memberId : partyData.getMembers()) {
            if (playerDataManager != null) {
                playerLevel = Math.max(playerLevel, playerDataManager.getPlayerLevel(memberId));
            }
        }
        final int lvl = playerLevel;

        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            PlayerRef playerRef = d.getPlayerRef();
            if (playerRef == null || !playerRef.isValid()) continue;
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) continue;
            Store<EntityStore> store = entityRef.getStore();
            World world = store.getExternalData().getWorld();

            var stats = new com.LucaStudios.HytaleDungeons.UI.VictoryPage.VictoryStats(
                    partyData.getCurrentFloor(),
                    partyData.getTotalMobsKilled(),
                    d.getTotalDeaths(),
                    partyData.getSharedRevivesRemaining(),
                    lvl,
                    partyData.getRunDurationMs());

            final com.LucaStudios.HytaleDungeons.UI.VictoryPage pageRef = victoryPage;
            final var s = stats;
            final PlayerRef pr = playerRef;
            final Store<EntityStore> st = store;
            world.execute(() -> {
                try {
                    pageRef.showFor(pr, st, s);
                } catch (Throwable t) {
                    plugin.getLogger().at(Level.WARNING).log("VictoryPage party showFor failed: " + t.getMessage());
                }
            });
        }
    }

    // ---- Game over ----

    private void onPartyGameOver(UUID partyId, PartyRunData partyData) {
        partyData.setState(RunState.GAME_OVER);
        log("Party %s game over on floor %d", partyId, partyData.getCurrentFloor());

        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            RunState old = d.getState();
            d.setState(RunState.GAME_OVER);
            fireStateChange(memberId, old, RunState.GAME_OVER, d);
        }

        if (gameOverPage == null) return;

        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            PlayerRef playerRef = d.getPlayerRef();
            if (playerRef == null || !playerRef.isValid()) continue;
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) continue;

            World world = entityRef.getStore().getExternalData().getWorld();
            var stats = new com.LucaStudios.HytaleDungeons.UI.GameOverPage.GameOverStats(
                    partyData.getCurrentFloor(),
                    partyData.getTotalMobsKilled(),
                    partyData.getSharedRevivesRemaining(),
                    partyData.getRunDurationMs());

            final com.LucaStudios.HytaleDungeons.UI.GameOverPage pageRef = gameOverPage;
            final UUID mid = memberId;
            final PlayerRef pr = playerRef;
            world.execute(() -> {
                try {
                    Ref<EntityStore> liveRef = pr.getReference();
                    if (liveRef != null && liveRef.isValid()) {
                        liveRef.getStore().tryRemoveComponent(liveRef, DeathComponent.getComponentType());
                    }
                } catch (Throwable ignored) {}
                if (healthManager != null) healthManager.resetHealth(mid);
            });
            scheduler.schedule(() -> world.execute(() -> {
                Ref<EntityStore> freshRef = pr.getReference();
                if (freshRef == null || !freshRef.isValid()) return;
                Store<EntityStore> freshStore = freshRef.getStore();
                if (freshStore == null) return;
                try {
                    pageRef.showFor(pr, freshStore, stats);
                } catch (Throwable t) {
                    plugin.getLogger().at(Level.WARNING).log("GameOverPage party showFor failed: " + t.getMessage());
                }
            }), 100L, TimeUnit.MILLISECONDS);
        }
    }

    // ---- Debug ----

    public void debugRegenFloor(UUID playerId, PlayerRef sender) {
        RunData data = runs.get(playerId);
        if (data == null) return;
        int floor = data.getCurrentFloor();
        World world = worldFromPlayerRef(sender);
        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);

        if (partyData != null) {
            Map<UUID, PlayerRef> memberRefs = collectMemberRefs(partyData.getMembers());
            int partySize = partyData.getMembers().size();
            floorGenerator.generatePartyFloor(partyId, floor, world, memberRefs, partySize, () -> {
                FloorData fd = floorGenerator.getActiveFloor(partyId);
                if (fd != null) partyData.setSharedMobsRemaining(fd.getMobSpawnCount());
            });
        } else {
            floorGenerator.generateFloor(playerId, floor, world, sender, () -> {
                FloorData fd = floorGenerator.getActiveFloor(playerId);
                if (fd != null) data.setMobsRemaining(fd.getMobSpawnCount());
            });
        }
    }

    public void debugFinishFloor(UUID playerId) {
        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);
        if (partyData != null) {
            if (partyData.getState() != RunState.FLOOR_ACTIVE) return;
            partyData.setSharedMobsRemaining(0);
            showPartyBetweenFloorsScreen(partyId, partyData);
        } else {
            RunData data = runs.get(playerId);
            if (data == null || data.getState() != RunState.FLOOR_ACTIVE) return;
            data.setMobsRemaining(0);
            showBetweenFloorsScreen(playerId, data);
        }
    }

    public void debugOpenGameOver(UUID playerId, PlayerRef playerRef) {
        if (gameOverPage == null || playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();
        var stats = new com.LucaStudios.HytaleDungeons.UI.GameOverPage.GameOverStats(3, 42, 0, 123456L);
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            Ref<EntityStore> freshRef = playerRef.getReference();
            if (freshRef == null || !freshRef.isValid()) return;
            Store<EntityStore> freshStore = freshRef.getStore();
            if (freshStore == null) return;
            gameOverPage.showFor(playerRef, freshStore, stats);
        });
    }

    // ---- New run / lobby ----

    public void onNewRunRequested(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || (data.getState() != RunState.GAME_OVER && data.getState() != RunState.VICTORY)) return;

        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);

        if (partyData != null) {
            restartPartyRun(partyId, partyData);
        } else {
            restartSoloRun(playerId, playerRef, data);
        }
    }

    private void restartSoloRun(UUID playerId, PlayerRef playerRef, RunData data) {
        boolean wasGameOver = data.getState() == RunState.GAME_OVER;
        RunState oldState = data.getState();
        data.reset(MAX_LIVES, STARTING_FLOOR);
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        if (wasGameOver) {
            if (playerRef != null && playerRef.isValid()) {
                Ref<EntityStore> entityRef = playerRef.getReference();
                if (entityRef != null && entityRef.isValid()) {
                    Store<EntityStore> store = entityRef.getStore();
                    try { store.tryRemoveComponent(entityRef, DeathComponent.getComponentType()); }
                    catch (Throwable ignored) {}
                }
            }
            if (healthManager != null) healthManager.resetHealth(playerId);
        }

        if (playerDataManager != null) playerDataManager.resetPlayer(playerId);
        log("Player %s starting new run", playerId);
        equipDefaultLoadout(playerRef);

        if (floorGenerator != null) {
            World world = worldFromPlayerRef(playerRef);
            floorGenerator.generateFloor(playerId, STARTING_FLOOR, world, playerRef, () -> {
                FloorData floor = floorGenerator.getActiveFloor(playerId);
                if (floor != null) data.setMobsRemaining(floor.getMobSpawnCount());
            });
        }
    }

    private void restartPartyRun(UUID partyId, PartyRunData partyData) {
        partyData.reset(MAX_LIVES, STARTING_FLOOR);
        Map<UUID, PlayerRef> memberRefs = collectMemberRefs(partyData.getMembers());
        World world = firstWorld(memberRefs);

        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            boolean wasGameOver = d.getState() == RunState.GAME_OVER;
            RunState old = d.getState();
            d.reset(MAX_LIVES, STARTING_FLOOR);
            fireStateChange(memberId, old, RunState.FLOOR_ACTIVE, d);

            PlayerRef pr = memberRefs.get(memberId);
            if (wasGameOver && pr != null && pr.isValid()) {
                Ref<EntityStore> er = pr.getReference();
                if (er != null && er.isValid()) {
                    try { er.getStore().tryRemoveComponent(er, DeathComponent.getComponentType()); }
                    catch (Throwable ignored) {}
                }
            }
            if (healthManager != null) healthManager.resetHealth(memberId);
            if (playerDataManager != null) playerDataManager.resetPlayer(memberId);
            equipDefaultLoadout(memberRefs.get(memberId));
        }

        if (floorGenerator != null && world != null) {
            int partySize = partyData.getMembers().size();
            floorGenerator.generatePartyFloor(partyId, STARTING_FLOOR, world, memberRefs, partySize, () -> {
                FloorData floor = floorGenerator.getActiveFloor(partyId);
                if (floor != null) partyData.setSharedMobsRemaining(floor.getMobSpawnCount());
            });
        }
    }

    public void setMobCount(UUID playerId, int count) {
        RunData data = runs.get(playerId);
        if (data != null) data.setMobsRemaining(count);
    }

    public void onReturnToLobby(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || (data.getState() != RunState.GAME_OVER && data.getState() != RunState.VICTORY)) return;

        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);

        if (partyData != null) {
            returnPartyToLobby(partyId, partyData);
        } else {
            returnSoloToLobby(playerId, playerRef, data);
        }
    }

    private void returnSoloToLobby(UUID playerId, PlayerRef playerRef, RunData data) {
        boolean wasGameOver = data.getState() == RunState.GAME_OVER;
        RunState oldState = data.getState();
        data.reset(MAX_LIVES, STARTING_FLOOR);
        data.setState(RunState.LOBBY);
        fireStateChange(playerId, oldState, RunState.LOBBY, data);

        if (wasGameOver) {
            if (playerRef != null && playerRef.isValid()) {
                Ref<EntityStore> entityRef = playerRef.getReference();
                if (entityRef != null && entityRef.isValid()) {
                    try { entityRef.getStore().tryRemoveComponent(entityRef, DeathComponent.getComponentType()); }
                    catch (Throwable ignored) {}
                }
            }
            if (healthManager != null) {
                try { healthManager.resetHealth(playerId); } catch (Throwable ignored) {}
            }
        }

        if (playerDataManager != null) {
            try { playerDataManager.resetPlayer(playerId); } catch (Throwable ignored) {}
        }
        log("Player %s returned to lobby", playerId);
    }

    private void returnPartyToLobby(UUID partyId, PartyRunData partyData) {
        for (UUID memberId : partyData.getMembers()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            boolean wasGameOver = d.getState() == RunState.GAME_OVER;
            RunState old = d.getState();
            d.reset(MAX_LIVES, STARTING_FLOOR);
            d.setState(RunState.LOBBY);
            fireStateChange(memberId, old, RunState.LOBBY, d);

            PlayerRef pr = d.getPlayerRef();
            if (wasGameOver && pr != null && pr.isValid()) {
                Ref<EntityStore> er = pr.getReference();
                if (er != null && er.isValid()) {
                    try { er.getStore().tryRemoveComponent(er, DeathComponent.getComponentType()); }
                    catch (Throwable ignored) {}
                }
                if (healthManager != null) {
                    try { healthManager.resetHealth(memberId); } catch (Throwable ignored) {}
                }
            }
            if (playerDataManager != null) {
                try { playerDataManager.resetPlayer(memberId); } catch (Throwable ignored) {}
            }
        }
        partyRuns.remove(partyId);
        log("Party %s returned to lobby", partyId);
    }

    // ---- Player join / leave ----

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();

        world.execute(() -> {
            if (!entityRef.isValid()) return;

            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) return;

            UUID playerId = playerRef.getUuid();

            FullBright.apply(playerRef);
            TopDownView.enable(playerRef);

            RunData data = new RunData(playerId, MAX_LIVES, STARTING_FLOOR);
            data.setPlayerRef(playerRef);
            data.setState(RunState.LOBBY);
            runs.put(playerId, data);

            if (healthManager != null) healthManager.initPlayer(playerId, playerRef, entityRef, store);
            if (playerDataManager != null) playerDataManager.initPlayer(playerId);

            fireStateChange(playerId, null, RunState.LOBBY, data);
            log("Player %s joined — entering lobby", playerId);
        });
    }

    /**
     * Start a run from the lobby for the clicking player. If they are in a party,
     * the run starts for all party members.
     */
    public void startRunFromLobby(UUID leaderId, PlayerRef leaderRef) {
        RunData leaderData = runs.get(leaderId);
        if (leaderData == null || leaderData.getState() != RunState.LOBBY) return;

        UUID partyId = partyId(leaderId);
        Set<UUID> memberIds = partyMembers(leaderId);

        if (memberIds.size() > 1) {
            // Party run: start for all members
            Map<UUID, PlayerRef> memberRefs = collectMemberRefs(memberIds);
            startPartyRun(partyId, memberIds, memberRefs);
        } else {
            // Solo run
            startSoloRun(leaderId, leaderRef, leaderData);
        }
    }

    private void startSoloRun(UUID playerId, PlayerRef playerRef, RunData data) {
        data.reset(MAX_LIVES, STARTING_FLOOR);
        RunState oldState = RunState.LOBBY;
        fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

        log("Player %s starting solo run from lobby", playerId);
        equipDefaultLoadout(playerRef);

        if (floorGenerator == null || playerRef == null || !playerRef.isValid()) return;
        World world = worldFromPlayerRef(playerRef);
        floorGenerator.generateFloor(playerId, STARTING_FLOOR, world, playerRef, () -> {
            FloorData floor = floorGenerator.getActiveFloor(playerId);
            if (floor != null) data.setMobsRemaining(floor.getMobSpawnCount());
        });
    }

    private void startPartyRun(UUID partyId, Set<UUID> memberIds, Map<UUID, PlayerRef> memberRefs) {
        // Only include members who are in LOBBY state
        Map<UUID, PlayerRef> lobbyMembers = new HashMap<>();
        for (UUID memberId : memberIds) {
            RunData d = runs.get(memberId);
            if (d != null && d.getState() == RunState.LOBBY) {
                PlayerRef pr = memberRefs.get(memberId);
                if (pr != null && pr.isValid()) lobbyMembers.put(memberId, pr);
            }
        }
        if (lobbyMembers.isEmpty()) return;

        // Create or reset party run data
        PartyRunData partyData = new PartyRunData(partyId, lobbyMembers.keySet(), MAX_LIVES, STARTING_FLOOR);
        partyRuns.put(partyId, partyData);

        // Initialize each member
        for (UUID memberId : lobbyMembers.keySet()) {
            RunData d = runs.get(memberId);
            if (d == null) continue;
            RunState old = d.getState();
            d.reset(MAX_LIVES, STARTING_FLOOR);
            fireStateChange(memberId, old, RunState.FLOOR_ACTIVE, d);
            if (playerDataManager != null) playerDataManager.resetPlayer(memberId);
            equipDefaultLoadout(lobbyMembers.get(memberId));
        }

        log("Party %s starting run with %d members", partyId, lobbyMembers.size());

        World world = firstWorld(lobbyMembers);
        if (floorGenerator == null || world == null) return;
        int partySize = lobbyMembers.size();
        floorGenerator.generatePartyFloor(partyId, STARTING_FLOOR, world, lobbyMembers, partySize, () -> {
            FloorData floor = floorGenerator.getActiveFloor(partyId);
            if (floor != null) partyData.setSharedMobsRemaining(floor.getMobSpawnCount());
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();

        // Handle downed timer cleanup
        ScheduledFuture<?> timer = downedTimers.remove(playerId);
        if (timer != null) timer.cancel(false);

        // Handle party disconnect
        UUID partyId = partyId(playerId);
        PartyRunData partyData = partyRuns.get(partyId);
        if (partyData != null) {
            partyData.setPlayerDowned(playerId, false);
            partyData.cancelDownedTimer(playerId);
            // Auto-complete their offer pick so remaining members aren't stuck
            if (partyData.getState() == RunState.UPGRADING) {
                if (partyData.markOfferPicked(playerId)) {
                    partyData.clearOfferPicks();
                    advancePartyFloor(partyId, partyData);
                }
            }
        }

        RunData removed = runs.remove(playerId);
        if (removed != null) {
            if (healthManager != null) healthManager.removePlayer(playerId);
            if (playerDataManager != null) playerDataManager.removePlayer(playerId);
            if (floorGenerator != null) floorGenerator.removePlayer(playerId);
            if (partyManager != null) partyManager.removePlayer(playerId);
            log("Player %s disconnected — run data cleaned up", playerId);
        }
    }

    // ---- Solo death resolution ----

    private void resolveDeathScreen(UUID playerId, PlayerRef playerRef) {
        RunData data = runs.get(playerId);
        if (data == null || data.getState() != RunState.DEAD) return;

        if (data.getLivesRemaining() > 0) {
            data.decrementLives();
            RunState oldState = data.getState();
            data.setState(RunState.FLOOR_ACTIVE);
            fireStateChange(playerId, oldState, RunState.FLOOR_ACTIVE, data);

            log("Player %s respawned on floor %d (%d lives left)", playerId, data.getCurrentFloor(), data.getLivesRemaining());

            World world = worldFromPlayerRef(playerRef);
            if (world != null) {
                world.execute(() -> revivePlayer(playerId, playerRef));
            }
        } else {
            RunState oldState = data.getState();
            data.setState(RunState.GAME_OVER);
            fireStateChange(playerId, oldState, RunState.GAME_OVER, data);

            log("Player %s game over on floor %d", playerId, data.getCurrentFloor());

            if (gameOverPage != null && playerRef != null && playerRef.isValid()) {
                Ref<EntityStore> entityRef = playerRef.getReference();
                if (entityRef != null && entityRef.isValid()) {
                    World world = entityRef.getStore().getExternalData().getWorld();
                    var stats = new com.LucaStudios.HytaleDungeons.UI.GameOverPage.GameOverStats(
                            data.getCurrentFloor(),
                            data.getTotalMobsKilled(),
                            data.getLivesRemaining(),
                            data.getRunDurationMs());
                    final com.LucaStudios.HytaleDungeons.UI.GameOverPage pageRef = gameOverPage;
                    world.execute(() -> {
                        try {
                            Ref<EntityStore> liveRef = playerRef.getReference();
                            if (liveRef != null && liveRef.isValid()) {
                                liveRef.getStore().tryRemoveComponent(liveRef, DeathComponent.getComponentType());
                            }
                        } catch (Throwable ignored) {}
                        if (healthManager != null) healthManager.resetHealth(playerId);
                    });
                    scheduler.schedule(() -> world.execute(() -> {
                        Ref<EntityStore> freshRef = playerRef.getReference();
                        if (freshRef == null || !freshRef.isValid()) return;
                        Store<EntityStore> freshStore = freshRef.getStore();
                        if (freshStore == null) return;
                        try {
                            pageRef.showFor(playerRef, freshStore, stats);
                        } catch (Throwable t) {
                            plugin.getLogger().at(Level.WARNING).log(
                                    "GameOverPage.showFor failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    }), 100L, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void showDeathPage(UUID playerId, PlayerRef playerRef, long durationMs, int revivesShown) {
        if (deathPage == null || playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();
        if (store == null) {
            plugin.getLogger().at(Level.WARNING).log("DeathPage: entityRef.getStore() null for player " + playerId);
            return;
        }
        World world = store.getExternalData().getWorld();
        final com.LucaStudios.HytaleDungeons.UI.DeathPage pageRef = deathPage;
        world.execute(() -> {
            try {
                Ref<EntityStore> liveRef = playerRef.getReference();
                if (liveRef != null && liveRef.isValid()) {
                    liveRef.getStore().tryRemoveComponent(liveRef, DeathComponent.getComponentType());
                }
            } catch (Throwable ignored) {}
            if (healthManager != null) healthManager.resetHealth(playerId);
            Ref<EntityStore> freshRef = playerRef.getReference();
            if (freshRef == null || !freshRef.isValid()) return;
            Store<EntityStore> freshStore = freshRef.getStore();
            if (freshStore == null) return;
            try {
                pageRef.showFor(playerRef, freshStore, durationMs, revivesShown, plugin);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).withCause(t).log("DeathPage.showFor failed");
            }
        });
    }

    private void revivePlayer(UUID playerId, PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        try { store.tryRemoveComponent(entityRef, DeathComponent.getComponentType()); }
        catch (Throwable ignored) {}

        if (healthManager != null) healthManager.resetHealth(playerId);
        TopDownView.enable(playerRef);

        if (floorGenerator != null) {
            RunData data = runs.get(playerId);
            int floor = (data != null) ? data.getCurrentFloor() : STARTING_FLOOR;
            World world = store.getExternalData().getWorld();
            floorGenerator.generateFloor(playerId, floor, world, playerRef, () -> {
                FloorData floorData = floorGenerator.getActiveFloor(playerId);
                if (floorData != null && data != null) {
                    data.setMobsRemaining(floorData.getMobSpawnCount());
                }
            });
        }
    }

    // ---- Helpers ----

    public UUID resolvePartyId(UUID playerId) {
        return partyId(playerId);
    }

    private UUID partyId(UUID playerId) {
        return partyManager != null ? partyManager.getPartyId(playerId) : playerId;
    }

    private Set<UUID> partyMembers(UUID playerId) {
        return partyManager != null ? partyManager.getPartyMembers(playerId) : Set.of(playerId);
    }

    private Map<UUID, PlayerRef> collectMemberRefs(Set<UUID> memberIds) {
        Map<UUID, PlayerRef> refs = new HashMap<>();
        for (UUID memberId : memberIds) {
            RunData d = runs.get(memberId);
            if (d != null && d.getPlayerRef() != null) refs.put(memberId, d.getPlayerRef());
        }
        return refs;
    }

    private static World firstWorld(Map<UUID, PlayerRef> memberRefs) {
        for (PlayerRef pr : memberRefs.values()) {
            World w = worldFromPlayerRef(pr);
            if (w != null) return w;
        }
        return null;
    }

    private void equipDefaultLoadout(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        world(store).execute(() -> {
            if (!entityRef.isValid()) return;
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

            if (storage != null) {
                storage.getInventory().setItemStackForSlot((short) 0, new ItemStack(DEFAULT_ARROW, 30));
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

    public record StateChangeEvent(UUID playerId, RunState oldState, RunState newState, RunData runData) {}
}
