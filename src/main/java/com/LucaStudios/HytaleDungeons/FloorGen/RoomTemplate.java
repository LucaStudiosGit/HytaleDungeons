package com.LucaStudios.HytaleDungeons.FloorGen;

import java.util.List;

/**
 * Immutable metadata for a room template, loaded from JSON.
 * Coordinates are relative to the room's local origin (0,0,0).
 */
public record RoomTemplate(
        String id,
        RoomType type,
        int sizeX, int sizeY, int sizeZ,
        int entryX, int entryY, int entryZ,
        int exitX, int exitY, int exitZ,
        int spawnPointX, int spawnPointY, int spawnPointZ,
        int exitZoneX, int exitZoneZ, int exitZoneWidth, int exitZoneDepth,
        List<int[]> mobSpawns,
        int minFloor,
        int maxFloor,
        List<String> tags
) {

    /**
     * Check if this template is eligible for a given floor number.
     */
    public boolean isEligibleForFloor(int floorNumber) {
        return floorNumber >= minFloor && floorNumber <= maxFloor;
    }
}
