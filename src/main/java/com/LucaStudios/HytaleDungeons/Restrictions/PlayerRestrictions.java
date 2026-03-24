package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class PlayerRestrictions {

    private final JavaPlugin plugin;

    public PlayerRestrictions(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(DamageBlockEvent.class, event -> event.setCancelled(true));
        plugin.getEventRegistry().registerGlobal(BreakBlockEvent.class, event -> event.setCancelled(true));
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        var entityRef = event.getPlayerRef();
        PlayerRef playerRef = entityRef.getStore().getComponent(entityRef, PlayerRef.getComponentType());
        if (playerRef == null) return;

        disableJump(playerRef);
    }

    private void disableJump(PlayerRef playerRef) {
        MovementManager movementManager = playerRef.getComponent(MovementManager.getComponentType());
        if (movementManager == null) return;

        var settings = movementManager.getSettings();
        settings.jumpForce = 0.0f;
        settings.swimJumpForce = 0.0f;

        var defaultSettings = movementManager.getDefaultSettings();
        if (defaultSettings != null) {
            defaultSettings.jumpForce = 0.0f;
            defaultSettings.swimJumpForce = 0.0f;
        }

        movementManager.update(playerRef.getPacketHandler());
    }
}
