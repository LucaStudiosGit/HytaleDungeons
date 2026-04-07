package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FloorTemplateLibraryTest {

    @BeforeAll
    static void loadTemplates() {
        FloorTemplateLibrary.load(msg -> {});
    }

    @Test
    void getInstance_notNullAfterLoad() {
        assertNotNull(FloorTemplateLibrary.getInstance());
    }

    @Test
    void hasAllRoomTypes() {
        FloorTemplateLibrary lib = FloorTemplateLibrary.getInstance();
        assertTrue(lib.floorCount() > 0, "Should have spawn rooms");
        assertTrue(lib.combatRoomCount() > 0, "Should have combat rooms");
        assertTrue(lib.exitRoomCount() > 0, "Should have exit rooms");
    }

    @Test
    void totalCount_matchesSum() {
        FloorTemplateLibrary lib = FloorTemplateLibrary.getInstance();
        assertEquals(
                lib.floorCount() + lib.combatRoomCount() + lib.exitRoomCount(),
                lib.totalCount()
        );
    }

    @Test
    void randomSpawnRoom_returnsSpawnType() {
        FloorTemplate room = FloorTemplateLibrary.getInstance().randomSpawnRoom(new Random(42));
        assertEquals(RoomType.SPAWN, room.type());
    }

    @Test
    void randomCombatRoom_returnsCombatType() {
        FloorTemplate room = FloorTemplateLibrary.getInstance().randomCombatRoom(new Random(42), 1);
        assertEquals(RoomType.COMBAT, room.type());
    }

    @Test
    void randomExitRoom_returnsExitType() {
        FloorTemplate room = FloorTemplateLibrary.getInstance().randomExitRoom(new Random(42));
        assertEquals(RoomType.EXIT, room.type());
    }

    @Test
    void randomCombatRoom_respectsFloorFilter() {
        FloorTemplateLibrary lib = FloorTemplateLibrary.getInstance();
        // Floor 1 should not return rooms with minFloor > 1
        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            FloorTemplate room = lib.randomCombatRoom(random, 1);
            assertTrue(room.isEligibleForFloor(1),
                    "Room " + room.id() + " has minFloor=" + room.minFloor() + " but was returned for floor 1");
        }
    }

    @Test
    void randomCombatRoom_highFloor_canReturnLateGameRooms() {
        FloorTemplateLibrary lib = FloorTemplateLibrary.getInstance();
        Random random = new Random(42);
        boolean foundLateRoom = false;
        for (int i = 0; i < 100; i++) {
            FloorTemplate room = lib.randomCombatRoom(random, 10);
            if (room.minFloor() > 1) {
                foundLateRoom = true;
                break;
            }
        }
        assertTrue(foundLateRoom, "High floor numbers should be able to return late-game rooms");
    }

    @Test
    void loadedTemplates_matchConfigCount() {
        // rooms.json has 2 spawn, 6 combat, 2 exit = 10 total
        FloorTemplateLibrary lib = FloorTemplateLibrary.getInstance();
        assertEquals(2, lib.floorCount());
        assertEquals(6, lib.combatRoomCount());
        assertEquals(2, lib.exitRoomCount());
        assertEquals(10, lib.totalCount());
    }
}
