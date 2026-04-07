package com.LucaStudios.HytaleDungeons.Enemies;

import java.util.List;

/**
 * One wave of enemies on a floor. Parsed from {@code floors.json -> spawnGroups[]}.
 *
 * @param id           unique id within the floor (e.g. {@code floor_01_wave_1})
 * @param triggerType  {@code "zone"} (fires on zPlane crossing) or {@code "on_cleared"}
 * @param zPlane       z coordinate of the trigger plane (only for {@code zone} triggers)
 * @param zones        spawn boxes; each defines a count of mobs to randomly place inside
 * @param nextGroupId  id of the group to chain into when this one is cleared, or null
 */
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
