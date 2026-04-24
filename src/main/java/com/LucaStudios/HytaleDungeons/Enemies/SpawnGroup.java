package com.LucaStudios.HytaleDungeons.Enemies;

import java.util.List;

public record SpawnGroup(
        String id,
        String triggerType,
        int zPlane,
        List<SpawnZone> zones,
        String nextGroupId
) {
    public static final String TRIGGER_ZONE = "zone";
    public static final String TRIGGER_ON_CLEARED = "on_cleared";

    public int totalMobCount() {
        int n = 0;
        for (SpawnZone z : zones) n += z.count();
        return n;
    }
}
