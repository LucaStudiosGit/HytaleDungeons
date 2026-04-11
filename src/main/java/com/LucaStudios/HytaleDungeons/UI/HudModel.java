package com.LucaStudios.HytaleDungeons.UI;

/**
 * Pure data snapshot + formatters for the game HUD. Has no dependency on
 * HyUI or Hytale types so it can be fully unit-tested.
 *
 * <p>Fields are wrapped in {@code formatXxx()} helpers to keep display
 * logic in one testable place.</p>
 */
public record HudModel(
        int currentHp,
        int maxHp,
        long potionCooldownRemainingMs,
        int arrowCount,
        int playerLevel,
        int livesRemaining) {

    /** Placeholder for arrows until ammo tracking exists. */
    public static final int PLACEHOLDER_ARROW_COUNT = 30;

    /** "READY" if off-cooldown, else "Xs" whole-seconds remaining (ceil). */
    public String formatPotion() {
        if (potionCooldownRemainingMs <= 0L) return "READY";
        long seconds = (potionCooldownRemainingMs + 999L) / 1000L;
        return seconds + "s";
    }

    public String formatArrows() {
        return String.valueOf(arrowCount);
    }

    public String formatLevel() {
        return "Lv " + playerLevel;
    }

    public String formatLives() {
        return String.valueOf(Math.max(0, livesRemaining));
    }

    /**
     * Current HP as a fraction of max, clamped to [0, 1]. Returns 0 if
     * max is non-positive. Used to scale the heart fill overlay.
     */
    public float hpRatio() {
        if (maxHp <= 0) return 0f;
        float r = (float) currentHp / (float) maxHp;
        if (r < 0f) return 0f;
        if (r > 1f) return 1f;
        return r;
    }
}
