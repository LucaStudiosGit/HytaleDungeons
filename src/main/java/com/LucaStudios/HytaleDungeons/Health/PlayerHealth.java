package com.LucaStudios.HytaleDungeons.Health;

import java.util.UUID;

/**
 * Per-player health state. Tracks HP and potion cooldown.
 * Thread-safe: all mutations happen on the world thread via HealthManager.
 */
public final class PlayerHealth {

    private final UUID playerId;
    private int currentHP;
    private int maxHP;
    private long potionCooldownEndMs;

    public PlayerHealth(UUID playerId, int maxHP) {
        this.playerId = playerId;
        this.maxHP = maxHP;
        this.currentHP = maxHP;
        this.potionCooldownEndMs = 0L;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getCurrentHP() {
        return currentHP;
    }

    public int getMaxHP() {
        return maxHP;
    }

    /**
     * Apply damage after armor reduction. Returns actual damage dealt.
     *
     * @param rawDamage     incoming damage before armor
     * @param damageReduction armor stat (flat subtraction)
     * @return actual damage dealt (minimum 1 if rawDamage > 0)
     */
    public int takeDamage(int rawDamage, int damageReduction) {
        if (currentHP <= 0) {
            return 0; // already dead
        }
        int clamped = Math.max(0, rawDamage);
        if (clamped == 0) {
            return 0;
        }
        int actual = Math.max(1, clamped - damageReduction);
        currentHP = Math.max(0, currentHP - actual);
        return actual;
    }

    /**
     * Use health potion. Returns HP actually healed (may be 0 if wasted at full).
     * Cooldown triggers regardless.
     */
    public int usePotion(int healAmount, long cooldownMs) {
        int before = currentHP;
        currentHP = Math.min(maxHP, currentHP + healAmount);
        potionCooldownEndMs = System.currentTimeMillis() + cooldownMs;
        return currentHP - before;
    }

    public boolean isPotionOnCooldown() {
        return System.currentTimeMillis() < potionCooldownEndMs;
    }

    public long getPotionCooldownRemainingMs() {
        long remaining = potionCooldownEndMs - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public boolean isDead() {
        return currentHP <= 0;
    }

    /**
     * Reset HP to max and clear potion cooldown.
     * Called on respawn, new floor, and new run.
     */
    public void reset() {
        currentHP = maxHP;
        potionCooldownEndMs = 0L;
    }
}
