package com.LucaStudios.HytaleDungeons;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class Main extends JavaPlugin {

    private static Main instance;

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        getLogger().at(Level.INFO).log("[TemplatePlugin] Plugin loaded!");
    }

    public static Main getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("[TemplatePlugin] Plugin setup!");
        
        registerEvents();
        registerCommands();
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("[TemplatePlugin] Plugin enabled!");

    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("[TemplatePlugin] Plugin disabled!");

    }

    private void registerEvents() {

    }

    private void registerCommands() {

    }

}
