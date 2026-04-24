package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;

import java.util.List;

public record FloorData(int floorNumber, float[] playerSpawnPoint, int fallY, List<SpawnGroup> spawnGroups) {

    public SpawnGroup findFirstZoneGroup() {
        for (SpawnGroup g : spawnGroups) {
            if (SpawnGroup.TRIGGER_ZONE.equals(g.triggerType())) return g;
        }
        return null;
    }

    public int getMobSpawnCount() {
        int n = 0;
        for (SpawnGroup g : spawnGroups) n += g.totalMobCount();
        return n;
    }
}
