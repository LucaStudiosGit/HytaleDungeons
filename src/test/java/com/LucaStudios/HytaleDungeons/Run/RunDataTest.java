package com.LucaStudios.HytaleDungeons.Run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunDataTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private RunData data;

    @BeforeEach
    void setUp() {
        data = new RunData(PLAYER_ID, 3, 1);
    }

    // --- Initial state ---

    @Test
    void initialStateIsFloorActive() {
        assertEquals(RunState.FLOOR_ACTIVE, data.getState());
    }

    @Test
    void initialFloorIsOne() {
        assertEquals(1, data.getCurrentFloor());
    }

    @Test
    void initialLivesMatchConstructor() {
        assertEquals(3, data.getLivesRemaining());
    }

    @Test
    void initialMobsRemainingIsZero() {
        assertEquals(0, data.getMobsRemaining());
    }

    @Test
    void playerIdIsPreserved() {
        assertEquals(PLAYER_ID, data.getPlayerId());
    }

    // --- Lives ---

    @Test
    void decrementLivesReducesByOne() {
        data.decrementLives();
        assertEquals(2, data.getLivesRemaining());
    }

    @Test
    void decrementLivesNeverGoesBelowZero() {
        data.decrementLives();
        data.decrementLives();
        data.decrementLives();
        data.decrementLives(); // 4th decrement — should stay at 0
        assertEquals(0, data.getLivesRemaining());
    }

    @Test
    void resetLivesRestoresToMaxLives() {
        data.decrementLives();
        data.decrementLives();
        data.resetLives(3);
        assertEquals(3, data.getLivesRemaining());
    }

    // --- Mobs ---

    @Test
    void setAndDecrementMobs() {
        data.setMobsRemaining(5);
        assertEquals(5, data.getMobsRemaining());

        data.decrementMobs();
        assertEquals(4, data.getMobsRemaining());
    }

    @Test
    void decrementMobsNeverGoesBelowZero() {
        data.setMobsRemaining(1);
        data.decrementMobs();
        data.decrementMobs(); // should stay at 0
        assertEquals(0, data.getMobsRemaining());
    }

    // --- Floor ---

    @Test
    void setCurrentFloor() {
        data.setCurrentFloor(5);
        assertEquals(5, data.getCurrentFloor());
    }

    // --- State ---

    @Test
    void setStateChangesState() {
        data.setState(RunState.DEAD);
        assertEquals(RunState.DEAD, data.getState());
    }

    // --- Reset ---

    @Test
    void resetRestoresAllFieldsToNewRunDefaults() {
        // Simulate mid-run state
        data.setCurrentFloor(7);
        data.decrementLives();
        data.decrementLives();
        data.setState(RunState.GAME_OVER);
        data.setMobsRemaining(12);

        // Reset
        data.reset(3, 1);

        assertEquals(RunState.FLOOR_ACTIVE, data.getState());
        assertEquals(1, data.getCurrentFloor());
        assertEquals(3, data.getLivesRemaining());
        assertEquals(0, data.getMobsRemaining());
    }

    @Test
    void resetWithCustomValues() {
        data.reset(5, 3);
        assertEquals(5, data.getLivesRemaining());
        assertEquals(3, data.getCurrentFloor());
    }
}
