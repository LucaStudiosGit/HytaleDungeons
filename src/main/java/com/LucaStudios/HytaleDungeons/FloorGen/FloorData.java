package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;

import java.util.List;

/**
 * Per-player floor state: spawn point and the floor's spawn groups.
 * Owned exclusively by {@link FloorGenerator}.
 */
public final class FloorData {

    private final int floorNumber;
    private final float[] playerSpawnPoint;
    private final List<SpawnGroup> spawnGroups;

    FloorData(int floorNumber, float[] spawnPoint, List<SpawnGroup> spawnGroups) {
        this.floorNumber = floorNumber;
        this.playerSpawnPoint = spawnPoint;
        this.spawnGroups = spawnGroups;
    }

    public int getFloorNumber() { return floorNumber; }
    public float[] getPlayerSpawnPoint() { return playerSpawnPoint; }
    public List<SpawnGroup> getSpawnGroups() { return spawnGroups; }

    public SpawnGroup findGroupById(String id) {
        for (SpawnGroup g : spawnGroups) {
            if (g.id().equals(id)) return g;
        }
        return null;
    }

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
