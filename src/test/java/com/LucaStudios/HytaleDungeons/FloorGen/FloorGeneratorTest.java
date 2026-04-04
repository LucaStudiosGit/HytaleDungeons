package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FloorGeneratorTest {

    private FloorGenerator generator;
    private final List<String> logs = new ArrayList<>();

    @BeforeAll
    static void loadTemplates() {
        RoomTemplateLibrary.load(msg -> {});
    }

    @BeforeEach
    void setUp() {
        logs.clear();
        generator = new FloorGenerator(logs::add);
    }

    // --- buildFloor (pure logic) ---

    @Test
    void buildFloor_hasCorrectRoomTypes() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        List<PlacedRoom> rooms = floor.getRooms();

        assertTrue(rooms.size() >= FloorGenerator.MIN_ROOMS);
        assertTrue(rooms.size() <= FloorGenerator.MAX_ROOMS);

        // First room is spawn, last is exit
        assertEquals(RoomType.SPAWN, rooms.getFirst().template().type());
        assertEquals(RoomType.EXIT, rooms.getLast().template().type());

        // Middle rooms are combat
        for (int i = 1; i < rooms.size() - 1; i++) {
            assertEquals(RoomType.COMBAT, rooms.get(i).template().type());
        }
    }

    @Test
    void buildFloor_roomsAreSpacedAlongZ() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        List<PlacedRoom> rooms = floor.getRooms();

        for (int i = 1; i < rooms.size(); i++) {
            int prevEnd = rooms.get(i - 1).worldZ() + rooms.get(i - 1).template().sizeZ();
            int gap = rooms.get(i).worldZ() - prevEnd;
            assertEquals(FloorGenerator.CORRIDOR_LENGTH, gap,
                    "Corridor between room " + (i - 1) + " and " + i + " should be " + FloorGenerator.CORRIDOR_LENGTH);
        }
    }

    @Test
    void buildFloor_allRoomsShareOriginXAndY() {
        int originX = 400;
        int originY = 100;
        FloorData floor = generator.buildFloor(1, originX, originY);

        for (PlacedRoom room : floor.getRooms()) {
            assertEquals(originX, room.worldX());
            assertEquals(originY, room.worldY());
        }
    }

    @Test
    void buildFloor_hasMobSpawns() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        // Combat rooms and exit rooms have mob spawns; total should be > 0
        assertTrue(floor.getMobSpawnCount() > 0);
    }

    @Test
    void buildFloor_playerSpawnPointIsInSpawnRoom() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        PlacedRoom spawnRoom = floor.getRooms().getFirst();

        int[] sp = floor.getPlayerSpawnPoint();
        // Spawn point should be within the spawn room's world bounds
        assertTrue(sp[0] >= spawnRoom.worldX());
        assertTrue(sp[0] <= spawnRoom.worldX() + spawnRoom.template().sizeX());
        assertTrue(sp[2] >= spawnRoom.worldZ());
        assertTrue(sp[2] <= spawnRoom.worldZ() + spawnRoom.template().sizeZ());
    }

    @Test
    void buildFloor_exitZoneIsInExitRoom() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        PlacedRoom exitRoom = floor.getRooms().getLast();

        int[] ez = floor.getExitZoneOrigin();
        assertTrue(ez[0] >= exitRoom.worldX());
        assertTrue(ez[2] >= exitRoom.worldZ());
    }

    @Test
    void buildFloor_totalSizeZIsCorrect() {
        FloorData floor = generator.buildFloor(1, 0, 100);
        List<PlacedRoom> rooms = floor.getRooms();
        PlacedRoom lastRoom = rooms.getLast();
        int expectedTotalZ = lastRoom.worldZ() + lastRoom.template().sizeZ();
        assertEquals(expectedTotalZ, floor.getTotalSizeZ());
    }

    @Test
    void buildFloor_floorNumberStored() {
        FloorData floor = generator.buildFloor(7, 0, 100);
        assertEquals(7, floor.getFloorNumber());
    }

    // --- generateFloor (lifecycle) ---

    @Test
    void generateFloor_invokesCallback() {
        AtomicBoolean called = new AtomicBoolean(false);
        UUID player = UUID.randomUUID();

        generator.generateFloor(player, 1, () -> called.set(true));

        assertTrue(called.get());
    }

    @Test
    void generateFloor_storesActiveFloor() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});

        FloorData floor = generator.getActiveFloor(player);
        assertNotNull(floor);
        assertEquals(1, floor.getFloorNumber());
    }

    @Test
    void generateFloor_cleansUpOldFloor() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});
        generator.generateFloor(player, 2, () -> {});

        FloorData floor = generator.getActiveFloor(player);
        assertEquals(2, floor.getFloorNumber());
    }

    @Test
    void cleanupFloor_removesActiveFloor() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});
        generator.cleanupFloor(player);

        assertNull(generator.getActiveFloor(player));
    }

    @Test
    void resetMobs_returnsCount() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});

        int count = generator.resetMobs(player);
        assertTrue(count > 0);
    }

    @Test
    void resetMobs_noFloor_returnsNegative() {
        assertEquals(-1, generator.resetMobs(UUID.randomUUID()));
    }

    @Test
    void removePlayer_cleansUpEverything() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});
        generator.removePlayer(player);

        assertNull(generator.getActiveFloor(player));
    }

    @Test
    void generateFloor_logs() {
        UUID player = UUID.randomUUID();
        generator.generateFloor(player, 1, () -> {});

        assertTrue(logs.stream().anyMatch(l -> l.contains("Floor 1 generated")));
    }

    // --- Player isolation ---

    @Test
    void differentPlayers_haveSeparateFloors() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        generator.generateFloor(player1, 1, () -> {});
        generator.generateFloor(player2, 3, () -> {});

        assertEquals(1, generator.getActiveFloor(player1).getFloorNumber());
        assertEquals(3, generator.getActiveFloor(player2).getFloorNumber());
    }

    @Test
    void differentPlayers_haveOffsetOrigins() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        generator.generateFloor(player1, 1, () -> {});
        generator.generateFloor(player2, 1, () -> {});

        int origin1 = generator.getActiveFloor(player1).getOriginX();
        int origin2 = generator.getActiveFloor(player2).getOriginX();
        assertNotEquals(origin1, origin2);
    }
}
