package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RightClickCrossbowHandler {

    private static final String LOADED_CROSSBOW_ITEM_ID = "Weapon_Crossbow_Iron";
    private static final float LOADED_CROSSBOW_AMMO = 1.0f;
    private static final String CROSSBOW_PROJECTILE_CONFIG_ID = "Projectile_Config_Arrow_Crossbow";
    private static final String ARROW_ITEM_ID = "Weapon_Arrow_Crude";

    private static final short FIRING_SLOT = 0;
    private static final long RESTORE_DELAY_MS = 360;

    private final CombatManager combatManager;

    private final ConcurrentHashMap<UUID, ItemStack> savedSlot0Items = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> restoreVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> restoreTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService restoreExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "right-click-crossbow-restore");
        t.setDaemon(true);
        return t;
    });

    public RightClickCrossbowHandler(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        if (event.getMouseButton().mouseButtonType != MouseButtonType.Right) return;
        if (event.getMouseButton().state != MouseButtonState.Released) return;

        Entity targetEntity = event.getTargetEntity();
        Vector3i targetBlock = event.getTargetBlock();
        if (targetEntity == null && targetBlock == null) return;

        PlayerRef playerRef = event.getPlayerRefComponent();
        if (!playerRef.isValid()) return;

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;

        Store<EntityStore> store = entityRef.getStore();
        var world = store.getExternalData().getWorld();

        world.execute(() -> handleFireRequest(playerRef, entityRef, store, targetEntity, targetBlock));
    }

    private void handleFireRequest(
            PlayerRef playerRef,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            Entity targetEntity,
            Vector3i targetBlock
    ) {
        if (!entityRef.isValid()) return;

        UUID playerId = playerRef.getUuid();

        ItemDefinition crossbow = ItemDatabase.getInstance().getByHytaleId(LOADED_CROSSBOW_ITEM_ID);
        if (!combatManager.canRangedAttack(playerId, crossbow)) return;

        var playerPos = playerRef.getTransform().getPosition();
        double shooterEyeY = playerPos.y + 1.5;
        Vector3d aimTarget = resolveAimTarget(targetEntity, targetBlock, shooterEyeY);
        if (aimTarget == null) return;

        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) return;

        InventoryComponent.Hotbar hotbar = store.getComponent(entityRef, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = store.getComponent(entityRef, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Backpack backpack = store.getComponent(entityRef, InventoryComponent.Backpack.getComponentType());
        if (hotbar == null) return;

        if (!consumeArrow(hotbar, storage, backpack)) return;

        savedSlot0Items.putIfAbsent(playerId, getSlot0Snapshot(hotbar));

        hotbar.getInventory().setItemStackForSlot(FIRING_SLOT, new ItemStack(LOADED_CROSSBOW_ITEM_ID));
        hotbar.setActiveSlot((byte) FIRING_SLOT);

        EntityStatMap entityStats = store.getComponent(entityRef, EntityStatMap.getComponentType());
        if (entityStats != null) {
            entityStats.getStatModifiersManager().recalculateEntityStatModifiers(entityRef, entityStats, store);
            entityStats.setStatValue(DefaultEntityStatTypes.getAmmo(), LOADED_CROSSBOW_AMMO);
            entityStats.update();
        }

        triggerCrossbowShot(playerRef, entityRef, store, aimTarget);
        combatManager.recordRangedAttack(playerId);

        player.invalidateEquipmentNetwork();

        scheduleRestore(playerRef);
    }

    private static final double AIM_CENTER_MASS_Y = 1.0;

    private Vector3d resolveAimTarget(Entity targetEntity, Vector3i targetBlock,
                                      double shooterEyeY) {
        if (targetEntity != null && targetEntity.getReference() != null && targetEntity.getReference().isValid()) {
            Ref<EntityStore> targetRef = targetEntity.getReference();
            TransformComponent tc = targetRef.getStore().getComponent(targetRef, TransformComponent.getComponentType());
            if (tc != null) {
                Vector3d pos = tc.getTransform().getPosition();
                return new Vector3d(pos.x, pos.y + AIM_CENTER_MASS_Y, pos.z);
            }
        }
        if (targetBlock != null) {
            return new Vector3d(targetBlock.x + 0.5, shooterEyeY, targetBlock.z + 0.5);
        }
        return null;
    }

    private ItemStack getSlot0Snapshot(InventoryComponent.Hotbar hotbar) {
        ItemStack itemStack = hotbar.getInventory().getItemStack(FIRING_SLOT);
        return itemStack == null ? ItemStack.EMPTY : itemStack;
    }

    private void triggerCrossbowShot(
            PlayerRef playerRef,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            Vector3d aimTarget
    ) {
        ProjectileConfig projectileConfig = ProjectileConfig.getAssetMap().getAsset(CROSSBOW_PROJECTILE_CONFIG_ID);
        if (projectileConfig == null) return;

        Transform transform = playerRef.getTransform();

        Vector3d position = transform.getPosition().clone();

        double dx = aimTarget.x - position.x;
        double dz = aimTarget.z - position.z;
        double horizonDist = Math.sqrt(dx * dx + dz * dz);
        if (horizonDist < 0.001) return;

        double dirX = dx / horizonDist;
        double dirZ = dz / horizonDist;
        double dy = aimTarget.y - (position.y + 1.5);

        float yaw = (float) Math.atan2(-dirX, dirZ);
        float pitch = (float) Math.atan2(dy, horizonDist);

        Vector3d spawnOffset = projectileConfig.getCalculatedOffset(yaw, pitch);
        Vector3d spawnPosition = position.add(spawnOffset).add(0.0, 1.5, 0.0);

        double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist3d < 0.001) return;

        Vector3d velocity = new Vector3d(
                dx / dist3d,
                dy / dist3d,
                dz / dist3d
        ).setLength(projectileConfig.getLaunchForce());

        final boolean[] spawned = {false};
        store.forEachChunk((chunk, commandBuffer) -> {
            if (spawned[0]) return;

            ProjectileModule.get().spawnProjectile(entityRef, commandBuffer, projectileConfig, spawnPosition, velocity);
            spawned[0] = true;
        });
    }

    private boolean consumeArrow(InventoryComponent.Hotbar hotbar,
                                  InventoryComponent.Storage storage,
                                  InventoryComponent.Backpack backpack) {
        return tryConsumeArrowFrom(hotbar != null ? hotbar.getInventory() : null)
                || tryConsumeArrowFrom(storage != null ? storage.getInventory() : null)
                || tryConsumeArrowFrom(backpack != null ? backpack.getInventory() : null);
    }

    private boolean tryConsumeArrowFrom(ItemContainer container) {
        if (container == null) return false;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack item = container.getItemStack(slot);
            if (item != null && !item.isEmpty() && ARROW_ITEM_ID.equals(item.getItemId())) {
                container.removeItemStackFromSlot(slot, 1);
                return true;
            }
        }
        return false;
    }

    private void scheduleRestore(PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();

        ScheduledFuture<?> oldTask = restoreTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        int version = restoreVersions.merge(playerId, 1, Integer::sum);

        ScheduledFuture<?> newTask = restoreExecutor.schedule(() -> {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                clearPendingRestoreState(playerId);
                return;
            }

            Store<EntityStore> store = entityRef.getStore();
            var world = store.getExternalData().getWorld();

            world.execute(() -> restoreSlot0IfLatest(playerId, version, entityRef, store));
        }, RESTORE_DELAY_MS, TimeUnit.MILLISECONDS);

        restoreTasks.put(playerId, newTask);
    }

    private void restoreSlot0IfLatest(
            UUID playerId,
            int version,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store
    ) {
        try {
            if (!entityRef.isValid()) return;

            Integer currentVersion = restoreVersions.get(playerId);
            if (currentVersion == null || currentVersion != version) {
                return;
            }

            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) return;

            InventoryComponent.Hotbar hotbar = store.getComponent(entityRef, InventoryComponent.Hotbar.getComponentType());
            if (hotbar == null) return;

            ItemStack originalItem = savedSlot0Items.get(playerId);
            if (originalItem == null || originalItem.isEmpty()) {
                hotbar.getInventory().removeItemStackFromSlot(FIRING_SLOT);
            } else {
                hotbar.getInventory().setItemStackForSlot(FIRING_SLOT, originalItem);
            }

            EntityStatMap entityStats = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (entityStats != null) {
                entityStats.setStatValue(DefaultEntityStatTypes.getAmmo(), 0.0f);
                entityStats.update();
            }

            player.invalidateEquipmentNetwork();
        } finally {
            Integer currentVersion = restoreVersions.get(playerId);
            if (currentVersion != null && currentVersion == version) {
                clearPendingRestoreState(playerId);
            }
        }
    }

    private void clearPendingRestoreState(UUID playerId) {
        ScheduledFuture<?> task = restoreTasks.remove(playerId);
        if (task != null) {
            task.cancel(false);
        }

        restoreVersions.remove(playerId);
        savedSlot0Items.remove(playerId);
    }

    public void shutdown() {
        for (ScheduledFuture<?> task : restoreTasks.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }

        restoreTasks.clear();
        restoreVersions.clear();
        savedSlot0Items.clear();

        restoreExecutor.shutdownNow();
    }
}