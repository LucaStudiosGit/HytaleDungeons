package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;

import java.util.function.Consumer;
import java.util.logging.Level;

public final class NoNaturalSpawnRestriction {

    private final JavaPlugin plugin;
    private final Consumer<String> logger;

    public NoNaturalSpawnRestriction(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = msg -> plugin.getLogger().at(Level.INFO).log(msg);
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(AddWorldEvent.class, this::onAddWorld);

        for (World world : Universe.get().getWorlds().values()) {
            disableNaturalSpawning(world);
        }
    }

    private void onAddWorld(AddWorldEvent event) {
        disableNaturalSpawning(event.getWorld());
    }

    private void disableNaturalSpawning(World world) {
        if (world == null) return;
        var config = world.getWorldConfig();
        if (config == null) return;
        if (!config.isSpawningNPC()) return;
        config.setSpawningNPC(false);
    }
}
