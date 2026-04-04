package com.LucaStudios.HytaleDungeons.Health;

import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages player health across the dungeon run.
 * Coordinates with RunStateManager for death events and state guards.
 *
 * @see PlayerHealth
 */
public final class HealthManager {

    // --- Tuning Knobs (from GDD) ---
    public static final int MAX_HP = 200;
    public static final int POTION_HEAL_AMOUNT = 50;
    public static final long POTION_COOLDOWN_MS = 8000L;
    public static final int MIN_DAMAGE = 1;

    private final RunStateManager runStateManager;
    private final ConcurrentHashMap<UUID, PlayerHealth> healthMap = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public HealthManager(RunStateManager runStateManager, Consumer<String> logger) {
        this.runStateManager = runStateManager;
        this.logger = logger;
    }

    /**
     * Initialize health tracking for a player. Called when a run starts.
     */
    public void initPlayer(UUID playerId) {
        healthMap.put(playerId, new PlayerHealth(playerId, MAX_HP));
    }

    /**
     * Remove health tracking for a player. Called on disconnect.
     */
    public void removePlayer(UUID playerId) {
        healthMap.remove(playerId);
    }

    /**
     * Get the health state for a player, or null if not tracked.
     */
    public PlayerHealth getHealth(UUID playerId) {
        return healthMap.get(playerId);
    }

    /**
     * Apply damage to a player. Checks run state — damage is ignored outside FLOOR_ACTIVE.
     *
     * @param playerId   the player taking damage
     * @param playerRef  the player ref (for death notification)
     * @param rawDamage  incoming damage before armor reduction
     * @param damageReduction armor's effective stat (flat subtraction)
     * @return actual damage dealt, or 0 if ignored
     */
    public int takeDamage(UUID playerId, PlayerRef playerRef, int rawDamage, int damageReduction) {
        // State guard: only process damage during FLOOR_ACTIVE
        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            return 0;
        }

        PlayerHealth health = healthMap.get(playerId);
        if (health == null || health.isDead()) {
            return 0;
        }

        int actual = health.takeDamage(rawDamage, damageReduction);

        if (actual > 0) {
            log("Player %s took %d damage (%d raw - %d armor), HP: %d/%d",
                    playerId, actual, rawDamage, damageReduction, health.getCurrentHP(), health.getMaxHP());
        }

        if (health.isDead()) {
            log("Player %s died (HP reached 0)", playerId);
            runStateManager.onPlayerDeath(playerId, playerRef);
        }

        return actual;
    }

    /**
     * Use health potion. Checks run state and cooldown.
     *
     * @return HP healed, or -1 if potion use was blocked (wrong state or cooldown)
     */
    public int usePotion(UUID playerId) {
        // State guard: only allow potions during FLOOR_ACTIVE
        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            return -1;
        }

        PlayerHealth health = healthMap.get(playerId);
        if (health == null || health.isDead()) {
            return -1;
        }

        if (health.isPotionOnCooldown()) {
            return -1;
        }

        int healed = health.usePotion(POTION_HEAL_AMOUNT, POTION_COOLDOWN_MS);
        log("Player %s used potion: healed %d HP, now %d/%d",
                playerId, healed, health.getCurrentHP(), health.getMaxHP());

        return healed;
    }

    /**
     * Reset HP and cooldown. Called on respawn, new floor, new run.
     */
    public void resetHealth(UUID playerId) {
        PlayerHealth health = healthMap.get(playerId);
        if (health != null) {
            health.reset();
            log("Player %s HP reset to %d", playerId, health.getMaxHP());
        }
    }

    private void log(String format, Object... args) {
        logger.accept(String.format(format, args));
    }
}
