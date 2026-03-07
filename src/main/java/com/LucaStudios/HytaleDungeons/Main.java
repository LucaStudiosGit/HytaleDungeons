package com.LucaStudios.HytaleDungeons;

import com.LucaStudios.HytaleDungeons.InventoryHandler.InventoryOpenDisabler;
import com.LucaStudios.HytaleDungeons.Pages.InventoryPage;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class Main extends JavaPlugin {

    private static Main instance;
    private InventoryOpenDisabler inventoryOpenDisabler;

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static Main getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin start!");

        registerEvents();
        registerCommands();
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin disabled!");
    }

    private void registerEvents() {
        inventoryOpenDisabler = new InventoryOpenDisabler(this, new InventoryPage());
        inventoryOpenDisabler.register();
    }

    private void registerCommands() {

    }

}
