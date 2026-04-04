package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;

import java.util.UUID;

/**
 * Enforces melee attack cooldowns by intercepting left-click events.
 * During cooldown, the click is cancelled — no animation, no swing, no damage.
 */
public final class MeleeCooldownHandler {

    private final CombatManager combatManager;

    public MeleeCooldownHandler(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        if (event.getMouseButton().mouseButtonType != MouseButtonType.Left) return;
        if (event.getMouseButton().state != MouseButtonState.Pressed) return;

        PlayerRef playerRef = event.getPlayerRefComponent();
        if (!playerRef.isValid()) return;

        UUID playerId = playerRef.getUuid();

        // Look up the player's active hotbar item to get weapon cooldown
        ItemDefinition weapon = getEquippedWeapon(playerRef);

        if (!combatManager.canMeleeAttack(playerId, weapon)) {
            event.setCancelled(true);
            return;
        }

        combatManager.recordMeleeAttack(playerId);
    }

    private ItemDefinition getEquippedWeapon(PlayerRef playerRef) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return ItemDefinition.FISTS;

        Store<EntityStore> store = entityRef.getStore();
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) return ItemDefinition.FISTS;

        Inventory inventory = player.getInventory();
        if (inventory == null) return ItemDefinition.FISTS;

        ItemStack activeItem = inventory.getHotbar().getItemStack(inventory.getActiveHotbarSlot());
        if (activeItem == null || activeItem.isEmpty()) return ItemDefinition.FISTS;

        return ItemDatabase.getInstance().getByHytaleId(activeItem.getItemId());
    }
}
