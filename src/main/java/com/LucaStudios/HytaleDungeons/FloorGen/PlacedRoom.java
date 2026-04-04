package com.LucaStudios.HytaleDungeons.FloorGen;

import java.util.List;

/**
 * A room template placed at a specific world position.
 * Stores the template reference and world-space coordinates.
 */
public record PlacedRoom(
        RoomTemplate template,
        int worldX, int worldY, int worldZ
) {

    /**
     * Convert a room-local mob spawn position to world coordinates.
     */
    public int[] toWorldMobSpawn(int[] localPos) {
        return new int[]{
                worldX + localPos[0],
                worldY + localPos[1],
                worldZ + localPos[2]
        };
    }

    /**
     * Get all mob spawn positions in world coordinates.
     */
    public List<int[]> worldMobSpawns() {
        return template.mobSpawns().stream()
                .map(this::toWorldMobSpawn)
                .toList();
    }

    /**
     * Get the player spawn point in world coordinates (for spawn rooms).
     */
    public int[] worldSpawnPoint() {
        return new int[]{
                worldX + template.spawnPointX(),
                worldY + template.spawnPointY(),
                worldZ + template.spawnPointZ()
        };
    }

    /**
     * Get the exit zone origin in world coordinates (for exit rooms).
     */
    public int[] worldExitZoneOrigin() {
        return new int[]{
                worldX + template.exitZoneX(),
                worldY,
                worldZ + template.exitZoneZ()
        };
    }
}
