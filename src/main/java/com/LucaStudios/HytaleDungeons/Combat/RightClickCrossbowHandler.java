package com.LucaStudios.HytaleDungeons.Combat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;

public final class RightClickCrossbowHandler {

    private static final String LOADED_CROSSBOW_ITEM_ID = "Weapon_Crossbow_Iron";
    private static final float LOADED_CROSSBOW_AMMO = 1.0f;
    private static final String CROSSBOW_PROJECTILE_CONFIG_ID = "Projectile_Config_Arrow_Crossbow";

    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        if (event.getMouseButton().mouseButtonType != MouseButtonType.Right) return;
        if (event.getMouseButton().state != MouseButtonState.Released) return;

        PlayerRef playerRef = event.getPlayerRefComponent();
        if (playerRef == null || !playerRef.isValid()) return;

        var entityRef = playerRef.getReference();
        var store = entityRef.getStore();
        var world = store.getExternalData().getWorld();

        world.execute(() -> {
            if (!entityRef.isValid()) return;

            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) return;

            Inventory inventory = player.getInventory();
            if (inventory == null) return;

            byte activeSlot = inventory.getActiveHotbarSlot();
            if (activeSlot == Inventory.INACTIVE_SLOT_INDEX) return;

            inventory.getHotbar().setItemStackForSlot((short) activeSlot, new ItemStack(LOADED_CROSSBOW_ITEM_ID));
            EntityStatMap entityStats = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (entityStats != null) {
                player.getStatModifiersManager().recalculateEntityStatModifiers(entityRef, entityStats, store);
                entityStats.setStatValue(DefaultEntityStatTypes.getAmmo(), LOADED_CROSSBOW_AMMO);
                entityStats.update();
            }

            triggerCrossbowShot(player, inventory, activeSlot, entityRef, store, entityStats);
            player.invalidateEquipmentNetwork();
            player.sendInventory();
        });
    }

    private void triggerCrossbowShot(
            Player player,
            Inventory inventory,
            byte activeSlot,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            EntityStatMap entityStats
    ) {
        ProjectileConfig projectileConfig = ProjectileConfig.getAssetMap().getAsset(CROSSBOW_PROJECTILE_CONFIG_ID);
        if (projectileConfig == null) return;
        if (player.getTransformComponent() == null) return;

        var transform = player.getTransformComponent().getTransform();
        Vector3d position = transform.getPosition().clone();
        var rotation = transform.getRotation();
        Vector3d spawnOffset = projectileConfig.getCalculatedOffset(rotation.getYaw(), rotation.getPitch());
        Vector3d spawnPosition = position.add(spawnOffset).add(0.0, 1.5, 0.0);
        Vector3d velocity = transform.getDirection().clone().setLength(projectileConfig.getLaunchForce());

        final boolean[] spawned = {false};
        store.forEachChunk((chunk, commandBuffer) -> {
            if (spawned[0]) return;

            ProjectileModule.get().spawnProjectile(entityRef, commandBuffer, projectileConfig, spawnPosition, velocity);
            spawned[0] = true;
        });

        if (spawned[0] && entityStats != null) {
            entityStats.setStatValue(DefaultEntityStatTypes.getAmmo(), 0.0f);
            entityStats.update();
        }

        if (spawned[0]) {
            inventory.getHotbar().removeItemStackFromSlot((short) activeSlot);
        }
    }
}
