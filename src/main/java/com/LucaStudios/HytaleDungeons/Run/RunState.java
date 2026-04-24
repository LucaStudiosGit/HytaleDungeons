package com.LucaStudios.HytaleDungeons.Run;

/**
 * All possible states in a dungeon run lifecycle.
 */
public enum RunState {

    /** Player is in the pre-run lobby — menu open, can look around but combat is off. */
    LOBBY(true, false),

    /** Player is fighting on a floor — movement and combat enabled. */
    FLOOR_ACTIVE(true, true),

    /** Floor cleared + exit reached — player choosing upgrade. */
    UPGRADING(false, false),

    /** Transition to the next floor — brief visual overlay. */
    DESCENDING(false, false),

    /** Player died — death screen showing lives remaining. */
    DEAD(false, false),

    /** All lives spent — score screen with new-run/quit options. */
    GAME_OVER(false, false),

    /** Final floor cleared — victory screen with run-over stats and new-run options. */
    VICTORY(false, false);

    private final boolean movementEnabled;
    private final boolean combatEnabled;

    RunState(boolean movementEnabled, boolean combatEnabled) {
        this.movementEnabled = movementEnabled;
        this.combatEnabled = combatEnabled;
    }

    public boolean isMovementEnabled() {
        return movementEnabled;
    }

    public boolean isCombatEnabled() {
        return combatEnabled;
    }
}
