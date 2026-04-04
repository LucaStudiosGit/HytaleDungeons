package com.LucaStudios.HytaleDungeons.FloorGen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-player floor state: the placed rooms, mob spawn positions, and world bounds.
 * Owned exclusively by {@link FloorGenerator}.
 */
public final class FloorData {

    private final int floorNumber;
    private final List<PlacedRoom> rooms;
    private final List<int[]> allMobSpawns;
    private final int[] playerSpawnPoint;
    private final int[] exitZoneOrigin;
    private final int exitZoneWidth;
    private final int exitZoneDepth;

    // World bounds for cleanup
    private final int originX, originY, originZ;
    private final int totalSizeX, totalSizeY, totalSizeZ;

    FloorData(int floorNumber, List<PlacedRoom> rooms,
              int originX, int originY, int originZ,
              int totalSizeX, int totalSizeY, int totalSizeZ) {
        this.floorNumber = floorNumber;
        this.rooms = Collections.unmodifiableList(rooms);
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.totalSizeX = totalSizeX;
        this.totalSizeY = totalSizeY;
        this.totalSizeZ = totalSizeZ;

        // Collect all mob spawns in world coords
        List<int[]> spawns = new ArrayList<>();
        for (PlacedRoom room : rooms) {
            spawns.addAll(room.worldMobSpawns());
        }
        this.allMobSpawns = Collections.unmodifiableList(spawns);

        // Find spawn point from the spawn room (first room)
        PlacedRoom spawnRoom = rooms.stream()
                .filter(r -> r.template().type() == RoomType.SPAWN)
                .findFirst()
                .orElse(rooms.get(0));
        this.playerSpawnPoint = spawnRoom.worldSpawnPoint();

        // Find exit zone from the exit room (last room)
        PlacedRoom exitRoom = rooms.stream()
                .filter(r -> r.template().type() == RoomType.EXIT)
                .findFirst()
                .orElse(rooms.get(rooms.size() - 1));
        this.exitZoneOrigin = exitRoom.worldExitZoneOrigin();
        this.exitZoneWidth = exitRoom.template().exitZoneWidth();
        this.exitZoneDepth = exitRoom.template().exitZoneDepth();
    }

    public int getFloorNumber() { return floorNumber; }
    public List<PlacedRoom> getRooms() { return rooms; }
    public List<int[]> getAllMobSpawns() { return allMobSpawns; }
    public int[] getPlayerSpawnPoint() { return playerSpawnPoint; }
    public int[] getExitZoneOrigin() { return exitZoneOrigin; }
    public int getExitZoneWidth() { return exitZoneWidth; }
    public int getExitZoneDepth() { return exitZoneDepth; }

    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public int getTotalSizeX() { return totalSizeX; }
    public int getTotalSizeY() { return totalSizeY; }
    public int getTotalSizeZ() { return totalSizeZ; }

    public int getMobSpawnCount() { return allMobSpawns.size(); }
}
