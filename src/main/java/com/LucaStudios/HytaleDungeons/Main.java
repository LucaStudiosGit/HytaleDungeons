package com.LucaStudios.HytaleDungeons;

import com.LucaStudios.HytaleDungeons.Config.LobbyConfig;
import com.LucaStudios.HytaleDungeons.Debug.DebugCommands;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.LucaStudios.HytaleDungeons.Combat.CombatManager;
import com.LucaStudios.HytaleDungeons.Combat.MeleeCooldownHandler;
import com.LucaStudios.HytaleDungeons.Combat.RightClickCrossbowHandler;
import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorTemplateLibrary;
import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.LucaStudios.HytaleDungeons.Restrictions.PlayerRestrictions;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.LucaStudios.HytaleDungeons.UI.GameHud;
import com.LucaStudios.HytaleDungeons.UI.MainMenuPage;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class Main extends JavaPlugin {

    private static Main instance;
    private RightClickCrossbowHandler rightClickCrossbowHandler;
    private RunStateManager runStateManager;
    private HealthManager healthManager;
    private CombatManager combatManager;
    private PlayerDataManager playerDataManager;
    private FloorGenerator floorGenerator;
    private EnemyManager enemyManager;

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static Main getInstance() {
        return instance;
    }

    public RunStateManager getRunStateManager() {
        return runStateManager;
    }

    public HealthManager getHealthManager() {
        return healthManager;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public FloorGenerator getFloorGenerator() {
        return floorGenerator;
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin start!");

        ItemDatabase.load(msg -> getLogger().at(Level.INFO).log(msg));
        FloorTemplateLibrary.load(msg -> getLogger().at(Level.INFO).log(msg));
        LobbyConfig.load(msg -> getLogger().at(Level.INFO).log(msg));

        registerEvents();
        registerCommands();
    }

    @Override
    protected void start() {
        applyWeatherLock();
    }

    private void applyWeatherLock() {
        String weatherId = LobbyConfig.getInstance().getWeather();
        if (weatherId == null || weatherId.isEmpty()) return;

        World world = Universe.get().getDefaultWorld();
        if (world == null) {
            getLogger().at(Level.WARNING).log("Weather lock: default world not available yet");
            return;
        }
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            WeatherResource resource = store.getResource(WeatherResource.getResourceType());
            if (resource == null) {
                getLogger().at(Level.WARNING).log("Weather lock: WeatherResource not found on world store");
                return;
            }
            resource.setForcedWeather(weatherId);
            int resolvedIndex = resource.getForcedWeatherIndex();
            if (resolvedIndex == 0) {
                getLogger().at(Level.WARNING).log(
                        "Weather lock: ID \"" + weatherId + "\" resolved to index 0 — unknown ID? " +
                        "IDs are case-sensitive (e.g. \"Zone3_Hedera\", not \"ZONE3_HEDERA\").");
            } else {
                getLogger().at(Level.INFO).log(
                        "Weather locked to \"" + weatherId + "\" (index " + resolvedIndex + ")");
            }
        });
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin disabled!");

        if (rightClickCrossbowHandler != null) {
            rightClickCrossbowHandler.shutdown();
        }
        if (runStateManager != null) {
            runStateManager.shutdown();
        }
    }

    private void registerEvents() {
        // Run State Machine — handles player join, disconnect, and run lifecycle
        runStateManager = new RunStateManager(this);

        // Player Data — tracks equipped gear, backpack, XP, and level
        playerDataManager = new PlayerDataManager(msg -> getLogger().at(Level.INFO).log(msg));

        enemyManager = new EnemyManager(runStateManager, msg -> getLogger().at(Level.INFO).log(msg));
        floorGenerator = new FloorGenerator(enemyManager, msg -> getLogger().at(Level.INFO).log(msg));

        // Health system — tracks HP, damage, and potions
        healthManager = new HealthManager(runStateManager, msg -> getLogger().at(Level.INFO).log(msg));
        runStateManager.setHealthManager(healthManager);
        runStateManager.setPlayerDataManager(playerDataManager);
        runStateManager.setFloorGenerator(floorGenerator);
        // Combat system — cooldowns, damage calculation
        combatManager = new CombatManager(runStateManager,
                msg -> getLogger().at(Level.INFO).log(msg));

        // ECS damage interceptor — rewrites player↔mob damage with our formulas
        // and hands off to the native pipeline for HP / FX / death.
        getEntityStoreRegistry().registerSystem(
                new com.LucaStudios.HytaleDungeons.Combat.DamageInterceptor(
                        enemyManager, combatManager, playerDataManager));

        // ECS death observer — bridges native DeathComponent back to our
        // live-count / chain-spawn bookkeeping.
        getEntityStoreRegistry().registerSystem(
                new com.LucaStudios.HytaleDungeons.Enemies.MobDeathObserver(
                        enemyManager,
                        msg -> getLogger().at(Level.INFO).log(msg)));

        // ECS death observer — catches player deaths and triggers our respawn flow.
        getEntityStoreRegistry().registerSystem(
                new com.LucaStudios.HytaleDungeons.Health.PlayerDeathObserver(
                        runStateManager,
                        msg -> getLogger().at(Level.INFO).log(msg)));

        runStateManager.register();

        // Re-apply weather lock on first player join in case start() ran before
        // the WeatherResource was initialised on the world store.
        getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                e -> applyWeatherLock());

        // Ranged attack handler with cooldown enforcement
        rightClickCrossbowHandler = new RightClickCrossbowHandler(combatManager);
        rightClickCrossbowHandler.register(this);

        // Melee cooldown enforcement — blocks left-click during weapon cooldown
        new MeleeCooldownHandler(combatManager).register(this);

        // Potion key watcher — must register BEFORE hotbar lock so the
        // watcher sees slot-4 presses before the lock filter consumes them.
        new com.LucaStudios.HytaleDungeons.Health.PotionKeyHandler(healthManager).register();

        new PlayerRestrictions(this).register();

        // Epic B — persistent game HUD covering native bottom bar
        // Constructed first so MainMenuPage can call show() on Start.
        GameHud gameHud = new GameHud(this, healthManager, playerDataManager, runStateManager);
        gameHud.register();

        // Epic A — main menu modal page on join
        new MainMenuPage(this, LobbyConfig.getInstance(), runStateManager, gameHud).register();

        // Debug commands — remove before release
        new DebugCommands(runStateManager, floorGenerator).register(this);
    }

    private void registerCommands() {

    }
}
