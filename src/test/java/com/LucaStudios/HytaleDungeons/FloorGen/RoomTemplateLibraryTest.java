package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RoomTemplateLibraryTest {

    @BeforeAll
    static void loadTemplates() {
        RoomTemplateLibrary.load(msg -> {});
    }

    @Test
    void getInstance_notNullAfterLoad() {
        assertNotNull(RoomTemplateLibrary.getInstance());
    }

    @Test
    void hasAllRoomTypes() {
        RoomTemplateLibrary lib = RoomTemplateLibrary.getInstance();
        assertTrue(lib.spawnRoomCount() > 0, "Should have spawn rooms");
        assertTrue(lib.combatRoomCount() > 0, "Should have combat rooms");
        assertTrue(lib.exitRoomCount() > 0, "Should have exit rooms");
    }

    @Test
    void totalCount_matchesSum() {
        RoomTemplateLibrary lib = RoomTemplateLibrary.getInstance();
        assertEquals(
                lib.spawnRoomCount() + lib.combatRoomCount() + lib.exitRoomCount(),
                lib.totalCount()
        );
    }

    @Test
    void randomSpawnRoom_returnsSpawnType() {
        RoomTemplate room = RoomTemplateLibrary.getInstance().randomSpawnRoom(new Random(42));
        assertEquals(RoomType.SPAWN, room.type());
    }

    @Test
    void randomCombatRoom_returnsCombatType() {
        RoomTemplate room = RoomTemplateLibrary.getInstance().randomCombatRoom(new Random(42), 1);
        assertEquals(RoomType.COMBAT, room.type());
    }

    @Test
    void randomExitRoom_returnsExitType() {
        RoomTemplate room = RoomTemplateLibrary.getInstance().randomExitRoom(new Random(42));
        assertEquals(RoomType.EXIT, room.type());
    }

    @Test
    void randomCombatRoom_respectsFloorFilter() {
        RoomTemplateLibrary lib = RoomTemplateLibrary.getInstance();
        // Floor 1 should not return rooms with minFloor > 1
        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            RoomTemplate room = lib.randomCombatRoom(random, 1);
            assertTrue(room.isEligibleForFloor(1),
                    "Room " + room.id() + " has minFloor=" + room.minFloor() + " but was returned for floor 1");
        }
    }

    @Test
    void randomCombatRoom_highFloor_canReturnLateGameRooms() {
        RoomTemplateLibrary lib = RoomTemplateLibrary.getInstance();
        Random random = new Random(42);
        boolean foundLateRoom = false;
        for (int i = 0; i < 100; i++) {
            RoomTemplate room = lib.randomCombatRoom(random, 10);
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
        RoomTemplateLibrary lib = RoomTemplateLibrary.getInstance();
        assertEquals(2, lib.spawnRoomCount());
        assertEquals(6, lib.combatRoomCount());
        assertEquals(2, lib.exitRoomCount());
        assertEquals(10, lib.totalCount());
    }
}
