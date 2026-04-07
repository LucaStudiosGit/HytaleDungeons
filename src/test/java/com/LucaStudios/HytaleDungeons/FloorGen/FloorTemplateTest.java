package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FloorTemplateTest {

    private FloorTemplate makeTemplate(int minFloor, int maxFloor) {
        return new FloorTemplate(
                "test_room", RoomType.COMBAT,
                20, 8, 20,
                10, 0, 0,
                10, 0, 19,
                0, 0, 0,
                0, 0, 0, 0,
                List.of(new int[]{5, 0, 10}),
                minFloor, maxFloor, List.of("test")
        );
    }

    @Test
    void eligibleForFloor_withinRange() {
        FloorTemplate t = makeTemplate(2, 5);
        assertTrue(t.isEligibleForFloor(2));
        assertTrue(t.isEligibleForFloor(3));
        assertTrue(t.isEligibleForFloor(5));
    }

    @Test
    void eligibleForFloor_outsideRange() {
        FloorTemplate t = makeTemplate(2, 5);
        assertFalse(t.isEligibleForFloor(1));
        assertFalse(t.isEligibleForFloor(6));
    }

    @Test
    void eligibleForFloor_singleFloor() {
        FloorTemplate t = makeTemplate(3, 3);
        assertFalse(t.isEligibleForFloor(2));
        assertTrue(t.isEligibleForFloor(3));
        assertFalse(t.isEligibleForFloor(4));
    }
}
