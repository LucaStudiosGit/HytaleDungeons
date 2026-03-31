package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

public final class NoBreakBlockRestriction {

    private final JavaPlugin plugin;

    public NoBreakBlockRestriction(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(BreakBlockEvent.class, event -> event.setCancelled(true));
    }
}
