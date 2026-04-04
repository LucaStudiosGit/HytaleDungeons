package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacedRoomTest {

    private RoomTemplate spawnTemplate() {
        return new RoomTemplate(
                "test_spawn", RoomType.SPAWN,
                16, 8, 16,
                8, 0, 0,   // entry
                8, 0, 15,  // exit
                8, 0, 8,   // spawnPoint
                0, 0, 0, 0,
                List.of(),
                1, 99, List.of()
        );
    }

    private RoomTemplate combatTemplate() {
        return new RoomTemplate(
                "test_combat", RoomType.COMBAT,
                20, 8, 20,
                10, 0, 0,
                10, 0, 19,
                0, 0, 0,
                0, 0, 0, 0,
                List.of(new int[]{5, 0, 10}, new int[]{15, 0, 10}),
                1, 99, List.of()
        );
    }

    private RoomTemplate exitTemplate() {
        return new RoomTemplate(
                "test_exit", RoomType.EXIT,
                16, 8, 20,
                8, 0, 0,
                8, 0, 19,
                0, 0, 0,
                6, 17, 4, 2,
                List.of(new int[]{5, 0, 8}),
                1, 99, List.of()
        );
    }

    @Test
    void worldMobSpawns_appliesOffset() {
        PlacedRoom room = new PlacedRoom(combatTemplate(), 100, 200, 300);
        List<int[]> spawns = room.worldMobSpawns();

        assertEquals(2, spawns.size());
        assertArrayEquals(new int[]{105, 200, 310}, spawns.get(0));
        assertArrayEquals(new int[]{115, 200, 310}, spawns.get(1));
    }

    @Test
    void worldMobSpawns_emptyForSpawnRoom() {
        PlacedRoom room = new PlacedRoom(spawnTemplate(), 0, 100, 0);
        assertTrue(room.worldMobSpawns().isEmpty());
    }

    @Test
    void worldSpawnPoint_appliesOffset() {
        PlacedRoom room = new PlacedRoom(spawnTemplate(), 50, 100, 200);
        int[] sp = room.worldSpawnPoint();
        assertArrayEquals(new int[]{58, 100, 208}, sp);
    }

    @Test
    void worldExitZoneOrigin_appliesOffset() {
        PlacedRoom room = new PlacedRoom(exitTemplate(), 50, 100, 200);
        int[] ez = room.worldExitZoneOrigin();
        assertArrayEquals(new int[]{56, 100, 217}, ez);
    }

    @Test
    void toWorldMobSpawn_singlePosition() {
        PlacedRoom room = new PlacedRoom(combatTemplate(), 10, 20, 30);
        int[] world = room.toWorldMobSpawn(new int[]{3, 1, 7});
        assertArrayEquals(new int[]{13, 21, 37}, world);
    }
}
