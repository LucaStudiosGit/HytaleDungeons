package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.Enemies.EnemyState;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
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

import java.util.UUID;

/**
 * ECS damage system that rewrites incoming {@link Damage} events so the native
 * Hytale pipeline handles HP changes, hit FX, damage numbers, death animation,
 * and corpse removal for both directions of combat.
 *
 * <ul>
 *   <li><b>Player &rarr; Mob</b>: computes weapon damage via
 *       {@link CombatManager#calculateMeleeDamage} and rewrites the event amount.
 *       Native pipeline subtracts from the mob's HEALTH stat.</li>
 *   <li><b>Mob &rarr; Player</b>: rewrites the amount to {@code max(1, mob.baseAtk - armor)}.
 *       Native pipeline subtracts from the player's HEALTH stat; when it crosses zero
 *       {@code DeathComponent} is added, which
 *       {@link com.LucaStudios.HytaleDungeons.Health.PlayerDeathObserver} observes
 *       to trigger our respawn flow.</li>
 * </ul>
 *
 * <p>Joins the native {@code filterDamageGroup}. {@code DamageSystems.ApplyDamage}
 * runs {@code AFTER filterDamageGroup}, so it sees our {@link Damage#setAmount}
 * value when it subtracts from the native HEALTH stat.</p>
 */
public final class DamageInterceptor extends DamageEventSystem {

    private final EnemyManager enemyManager;
    private final CombatManager combatManager;
    private final PlayerDataManager playerDataManager;

    public DamageInterceptor(EnemyManager enemyManager,
                             CombatManager combatManager,
                             PlayerDataManager playerDataManager) {
        this.enemyManager = enemyManager;
        this.combatManager = combatManager;
        this.playerDataManager = playerDataManager;
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
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        if (damage.isCancelled()) return;

        Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
        if (victimRef == null) return;

        Ref<EntityStore> attackerRef = null;
        Damage.Source source = damage.getSource();
        if (source instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();
        }

        // Player → Mob
        EnemyState victimState = enemyManager.getState(victimRef);
        if (victimState != null && attackerRef != null) {
            handlePlayerAttacksMob(victimState, attackerRef, store, damage);
            return;
        }

        // Mob → Player
        if (attackerRef != null) {
            EnemyState attackerState = enemyManager.getState(attackerRef);
            if (attackerState != null) {
                handleMobAttacksPlayer(victimRef, attackerState, store, damage);
            }
        }
    }

    /**
     * Player hit a tracked mob. Replace the native damage amount with our
     * weapon-based damage and let the native pipeline subtract, play hit FX,
     * and trigger death when HP hits zero.
     */
    private void handlePlayerAttacksMob(EnemyState victimState,
                                        Ref<EntityStore> attackerRef,
                                        Store<EntityStore> store,
                                        Damage damage) {
        Player player = store.getComponent(attackerRef, Player.getComponentType());
        if (player == null) return;

        UUID playerId = victimState.playerId;
        int playerLevel = playerDataManager.getPlayerLevel(playerId);
        ItemDefinition weapon = playerDataManager.getEquippedWeapon(playerId);
        int weaponDamage = combatManager.calculateMeleeDamage(weapon, playerLevel);

        damage.setAmount((float) weaponDamage);
    }

    /**
     * A tracked mob hit a player. Compute {@code baseAtk - armor}, rewrite the
     * event amount, and let the native pipeline apply it. If the hit is lethal,
     * the native pipeline adds {@code DeathComponent}, which
     * {@code PlayerDeathObserver} observes to trigger our death/respawn flow.
     */
    private void handleMobAttacksPlayer(Ref<EntityStore> victimRef,
                                        EnemyState attackerState,
                                        Store<EntityStore> store,
                                        Damage damage) {
        Player player = store.getComponent(victimRef, Player.getComponentType());
        if (player == null) return;

        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) return;

        UUID playerId = playerRef.getUuid();
        int enemyAtk = attackerState.baseAtk;

        int playerLevel = playerDataManager.getPlayerLevel(playerId);
        ItemDefinition armor = playerDataManager.getEquippedArmor(playerId);
        int armorReduction = (armor != null) ? armor.getEffectiveStat(playerLevel) : 0;

        int finalDamage = Math.max(1, enemyAtk - armorReduction);
        damage.setAmount((float) finalDamage);
    }
}
