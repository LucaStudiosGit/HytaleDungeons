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
    void allNonActiveStatesDisableMovementAndCombat() {
        for (RunState state : RunState.values()) {
            if (state == RunState.FLOOR_ACTIVE) continue;
            assertFalse(state.isMovementEnabled(),
                    state + " should disable movement");
            assertFalse(state.isCombatEnabled(),
                    state + " should disable combat");
        }
    }

    @Test
    void hasAllFiveStates() {
        assertEquals(5, RunState.values().length);
        assertNotNull(RunState.FLOOR_ACTIVE);
        assertNotNull(RunState.UPGRADING);
        assertNotNull(RunState.DESCENDING);
        assertNotNull(RunState.DEAD);
        assertNotNull(RunState.GAME_OVER);
    }
}
