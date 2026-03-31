package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

public final class NoDamageBlockRestriction {

    private final JavaPlugin plugin;

    public NoDamageBlockRestriction(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(DamageBlockEvent.class, event -> event.setCancelled(true));
    }
}
