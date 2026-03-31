package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

public final class NoHotbarSwitchRestriction {

    private final JavaPlugin plugin;

    public NoHotbarSwitchRestriction(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(SwitchActiveSlotEvent.class, event -> {
            if (event.getInventorySectionId() == Inventory.HOTBAR_SECTION_ID && event.isClientRequest()) {
                event.setCancelled(true);
            }
        });
    }
}
