package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages combat logic: attack cooldowns, damage calculation, and enemy HP.
 * Bridges Hytale's native attacks to our damage pipeline.
 */
public final class CombatManager {

    private final RunStateManager runStateManager;
    private final HealthManager healthManager;
    private final Consumer<String> logger;

    /** Per-player timestamp of last melee attack (for cooldown enforcement). */
    private final ConcurrentHashMap<UUID, Long> lastMeleeTime = new ConcurrentHashMap<>();

    /** Per-player timestamp of last ranged attack (for cooldown enforcement). */
    private final ConcurrentHashMap<UUID, Long> lastRangedTime = new ConcurrentHashMap<>();

    public CombatManager(RunStateManager runStateManager, HealthManager healthManager,
                         Consumer<String> logger) {
        this.runStateManager = runStateManager;
        this.healthManager = healthManager;
        this.logger = logger;
    }

    /**
     * Check if a player can perform a melee attack right now.
     */
    public boolean canMeleeAttack(UUID playerId, ItemDefinition weapon) {
        return canAttack(playerId, weapon, lastMeleeTime);
    }

    /**
     * Check if a player can perform a ranged attack right now.
     */
    public boolean canRangedAttack(UUID playerId, ItemDefinition crossbow) {
        return canAttack(playerId, crossbow, lastRangedTime);
    }

    /**
     * Record that a melee attack was performed (starts melee cooldown).
     */
    public void recordMeleeAttack(UUID playerId) {
        lastMeleeTime.put(playerId, System.currentTimeMillis());
    }

    /**
     * Record that a ranged attack was performed (starts ranged cooldown).
     */
    public void recordRangedAttack(UUID playerId) {
        lastRangedTime.put(playerId, System.currentTimeMillis());
    }

    private boolean canAttack(UUID playerId, ItemDefinition item,
                              ConcurrentHashMap<UUID, Long> cooldownMap) {
        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastTime = cooldownMap.get(playerId);
        if (lastTime != null && (now - lastTime) < item.getCooldownMs()) {
            return false;
        }

        return true;
    }

    /**
     * Calculate melee damage from the player's equipped weapon.
     *
     * @param playerLevel the player's current level
     * @return damage value (floored integer)
     */
    public int calculateMeleeDamage(ItemDefinition weapon, int playerLevel) {
        return weapon.getEffectiveStat(playerLevel);
    }

    /**
     * Calculate ranged damage from the player's equipped crossbow.
     *
     * @param playerLevel the player's current level
     * @return damage value (floored integer)
     */
    public int calculateRangedDamage(ItemDefinition crossbow, int playerLevel) {
        return crossbow.getEffectiveStat(playerLevel);
    }

    /**
     * Process damage from an enemy to a player.
     * Delegates to HealthManager which handles armor reduction and death.
     *
     * @param playerId target player
     * @param playerRef target player ref
     * @param enemyAttackDamage raw damage from the enemy type
     * @param playerDamageReduction equipped armor's effective stat
     * @return actual damage dealt after armor reduction
     */
    public int processEnemyDamageToPlayer(UUID playerId, PlayerRef playerRef,
                                           int enemyAttackDamage, int playerDamageReduction) {
        return healthManager.takeDamage(playerId, playerRef, enemyAttackDamage, playerDamageReduction);
    }

    /**
     * Clean up tracking for a disconnected player.
     */
    public void removePlayer(UUID playerId) {
        lastMeleeTime.remove(playerId);
        lastRangedTime.remove(playerId);
    }
}
