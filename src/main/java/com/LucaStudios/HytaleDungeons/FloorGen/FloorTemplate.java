package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;

import java.util.List;

/**
 * Immutable metadata for a floor template, loaded from JSON.
 * Coordinates are world-space.
 */
public record FloorTemplate(
        int floorNumber,
        float spawnPointX, float spawnPointY, float spawnPointZ,
        int exitZoneX, int exitZoneY, int exitZoneZ, int exitZoneWidth, int exitZoneDepth,
        List<SpawnGroup> spawnGroups
) {
}
