package com.LucaStudios.HytaleDungeons;

import com.LucaStudios.HytaleDungeons.Debug.DebugCommands;
import com.LucaStudios.HytaleDungeons.Combat.CombatManager;
import com.LucaStudios.HytaleDungeons.Combat.MeleeCooldownHandler;
import com.LucaStudios.HytaleDungeons.Combat.RightClickCrossbowHandler;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.FloorGen.RoomTemplateLibrary;
import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.LucaStudios.HytaleDungeons.Restrictions.PlayerRestrictions;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
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
        RoomTemplateLibrary.load(msg -> getLogger().at(Level.INFO).log(msg));

        registerEvents();
        registerCommands();
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

        // Health system — tracks HP, damage, and potions
        healthManager = new HealthManager(runStateManager, msg -> getLogger().at(Level.INFO).log(msg));
        runStateManager.setHealthManager(healthManager);
        runStateManager.setPlayerDataManager(playerDataManager);

        // Floor generation — builds dungeon layouts
        floorGenerator = new FloorGenerator(msg -> getLogger().at(Level.INFO).log(msg));
        runStateManager.setFloorGenerator(floorGenerator);

        // Combat system — cooldowns, damage calculation
        combatManager = new CombatManager(runStateManager, healthManager,
                msg -> getLogger().at(Level.INFO).log(msg));

        runStateManager.register();

        // Ranged attack handler with cooldown enforcement
        rightClickCrossbowHandler = new RightClickCrossbowHandler(combatManager);
        rightClickCrossbowHandler.register(this);

        // Melee cooldown enforcement — blocks left-click during weapon cooldown
        new MeleeCooldownHandler(combatManager).register(this);

        new PlayerRestrictions(this).register();

        // Debug commands — remove before release
        new DebugCommands(runStateManager, floorGenerator).register(this);
    }

    private void registerCommands() {

    }
}
