package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.Enemies.EnemyState;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class DamageInterceptor extends DamageEventSystem {

    private final EnemyManager enemyManager;
    private final CombatManager combatManager;
    private final PlayerDataManager playerDataManager;
    private final RunStateManager runStateManager;
    private final DodgeManager dodgeManager;

    public DamageInterceptor(EnemyManager enemyManager,
                             CombatManager combatManager,
                             PlayerDataManager playerDataManager,
                             RunStateManager runStateManager,
                             DodgeManager dodgeManager) {
        this.enemyManager = enemyManager;
        this.combatManager = combatManager;
        this.playerDataManager = playerDataManager;
        this.runStateManager = runStateManager;
        this.dodgeManager = dodgeManager;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public void handle(int index,
                       @NotNull ArchetypeChunk<EntityStore> chunk,
                       @NotNull Store<EntityStore> store,
                       @NotNull CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        if (damage.isCancelled()) return;

        Ref<EntityStore> victimRef = chunk.getReferenceTo(index);

        Ref<EntityStore> attackerRef = null;
        Damage.Source source = damage.getSource();
        if (source instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();
        }

        EnemyState victimState = enemyManager.getState(victimRef);
        if (victimState != null && attackerRef != null) {
            handlePlayerAttacksMob(victimState, attackerRef, store, damage);
            return;
        }

        if (attackerRef != null) {
            EnemyState attackerState = enemyManager.getState(attackerRef);
            if (attackerState != null) {
                handleMobAttacksPlayer(victimRef, attackerState, store, damage);
            }
        }
    }

    private void handlePlayerAttacksMob(EnemyState victimState,
                                        Ref<EntityStore> attackerRef,
                                        Store<EntityStore> store,
                                        Damage damage) {
        // Verify the attacker is a player and get their UUID
        Player player = store.getComponent(attackerRef, Player.getComponentType());
        if (player == null) return;

        PlayerRef attackerPlayerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayerRef == null) return;
        UUID attackerId = attackerPlayerRef.getUuid();

        ItemDefinition weapon = playerDataManager.getEquippedWeapon(attackerId);
        int weaponLevel = playerDataManager.getEquippedWeaponLevel(attackerId);
        int weaponDamage = combatManager.calculateMeleeDamage(weapon, weaponLevel);

        damage.setAmount((float) weaponDamage);
    }

    private void handleMobAttacksPlayer(Ref<EntityStore> victimRef,
                                        EnemyState attackerState,
                                        Store<EntityStore> store,
                                        Damage damage) {
        Player player = store.getComponent(victimRef, Player.getComponentType());
        if (player == null) return;

        // Use the actual victim's UUID for all lookups
        PlayerRef victimPlayerRef = store.getComponent(victimRef, PlayerRef.getComponentType());
        if (victimPlayerRef == null) return;
        UUID victimId = victimPlayerRef.getUuid();

        if (dodgeManager != null && dodgeManager.isInIFrames(victimId)) {
            damage.setCancelled(true);
            return;
        }

        RunData runData = runStateManager.getRunData(victimId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            damage.setCancelled(true);
            return;
        }

        int enemyAtk = attackerState.baseAtk();

        ItemDefinition armor = playerDataManager.getEquippedArmor(victimId);
        int armorLevel = playerDataManager.getEquippedArmorLevel(victimId);
        int armorReduction = (armor != null) ? armor.getEffectiveStat(armorLevel) : 0;

        int finalDamage = Math.max(1, enemyAtk - armorReduction);
        damage.setAmount((float) finalDamage);
    }
}
