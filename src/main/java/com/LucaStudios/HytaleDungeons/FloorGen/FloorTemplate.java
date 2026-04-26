package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;

import java.util.List;

public record FloorTemplate(
        int floorNumber,
        float spawnPointX, float spawnPointY, float spawnPointZ,
        int exitZoneX, int exitZoneY, int exitZoneZ, int exitZoneWidth, int exitZoneDepth,
        int fallY,
        List<SpawnGroup> spawnGroups
) {
}
