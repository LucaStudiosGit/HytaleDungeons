package com.LucaStudios.HytaleDungeons.Run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunStateTest {

    @Test
    void floorActiveEnablesMovementAndCombat() {
        assertTrue(RunState.FLOOR_ACTIVE.isMovementEnabled());
        assertTrue(RunState.FLOOR_ACTIVE.isCombatEnabled());
    }

    @Test
    void lobbyAllowsMovementButNotCombat() {
        assertTrue(RunState.LOBBY.isMovementEnabled(),
                "Lobby should let the player walk around");
        assertFalse(RunState.LOBBY.isCombatEnabled(),
                "Lobby should not enable combat");
    }

    @Test
    void allNonActiveStatesDisableCombat() {
        for (RunState state : RunState.values()) {
            if (state == RunState.FLOOR_ACTIVE) continue;
            assertFalse(state.isCombatEnabled(),
                    state + " should disable combat");
        }
    }

    @Test
    void runEndingStatesDisableMovement() {
        // Movement stays enabled in LOBBY/FLOOR_ACTIVE; every other state freezes the player.
        for (RunState state : RunState.values()) {
            if (state == RunState.FLOOR_ACTIVE || state == RunState.LOBBY) continue;
            assertFalse(state.isMovementEnabled(),
                    state + " should disable movement");
        }
    }

    @Test
    void hasAllStates() {
        assertEquals(7, RunState.values().length);
        assertNotNull(RunState.LOBBY);
        assertNotNull(RunState.FLOOR_ACTIVE);
        assertNotNull(RunState.UPGRADING);
        assertNotNull(RunState.DESCENDING);
        assertNotNull(RunState.DEAD);
        assertNotNull(RunState.GAME_OVER);
        assertNotNull(RunState.VICTORY);
    }
}
