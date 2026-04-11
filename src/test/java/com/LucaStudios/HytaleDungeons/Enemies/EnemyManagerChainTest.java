package com.LucaStudios.HytaleDungeons.Enemies;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers Option B wiring: floor registration, live-count tracking,
 * and {@code on_cleared} chain dispatch. Bypasses the NPC API entirely
 * by seeding live counts through the package-private {@code incrementLive}
 * hook and intercepting chain spawns via the {@code chainSpawner} seam.
 */
class EnemyManagerChainTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private EnemyManager mgr;
    private AtomicReference<SpawnGroup> lastChainedGroup;

    @BeforeEach
    void setUp() {
        mgr = new EnemyManager(null, msg -> { /* silent */ });
        lastChainedGroup = new AtomicReference<>();
        mgr.chainSpawner = (playerId, group) -> lastChainedGroup.set(group);
    }

    private SpawnGroup group(String id, int count, String nextGroupId) {
        List<SpawnZone> zones = new ArrayList<>();
        zones.add(new SpawnZone(0, 0, 0, 0, 0, 0, count));
        return new SpawnGroup(id, SpawnGroup.TRIGGER_ZONE, 0, zones, nextGroupId);
    }

    @Test
    void registerFloorInitializesZeroLiveCounts() {
        SpawnGroup a = group("a", 2, null);
        mgr.registerFloorForTest(PLAYER, List.of(a));
        assertEquals(0, mgr.getLiveCount(PLAYER, "a"));
    }

    @Test
    void incrementLiveTracksPerGroup() {
        SpawnGroup a = group("a", 3, null);
        mgr.registerFloorForTest(PLAYER, List.of(a));
        mgr.incrementLive(PLAYER, "a");
        mgr.incrementLive(PLAYER, "a");
        assertEquals(2, mgr.getLiveCount(PLAYER, "a"));
    }

    @Test
    void onMobDiedDecrementsLiveCount() {
        SpawnGroup a = group("a", 2, null);
        mgr.registerFloorForTest(PLAYER, List.of(a));
        mgr.incrementLive(PLAYER, "a");
        mgr.incrementLive(PLAYER, "a");

        mgr.onMobDied(PLAYER, "a");
        assertEquals(1, mgr.getLiveCount(PLAYER, "a"));
        assertNull(lastChainedGroup.get(), "chain must not fire while mobs remain");
    }

    @Test
    void clearingLastMobFiresChainToNextGroup() {
        SpawnGroup a = group("a", 1, "b");
        SpawnGroup b = group("b", 2, null);
        mgr.registerFloorForTest(PLAYER, List.of(a, b));
        mgr.incrementLive(PLAYER, "a");

        mgr.onMobDied(PLAYER, "a");

        assertEquals(0, mgr.getLiveCount(PLAYER, "a"));
        assertNotNull(lastChainedGroup.get());
        assertEquals("b", lastChainedGroup.get().id());
    }

    @Test
    void clearingGroupWithoutNextDoesNotChain() {
        SpawnGroup solo = group("solo", 1, null);
        mgr.registerFloorForTest(PLAYER, List.of(solo));
        mgr.incrementLive(PLAYER, "solo");

        mgr.onMobDied(PLAYER, "solo");

        assertEquals(0, mgr.getLiveCount(PLAYER, "solo"));
        assertNull(lastChainedGroup.get());
    }

    @Test
    void chainToUnknownGroupIdIsIgnored() {
        SpawnGroup a = group("a", 1, "ghost");
        mgr.registerFloorForTest(PLAYER, List.of(a));
        mgr.incrementLive(PLAYER, "a");

        mgr.onMobDied(PLAYER, "a");

        assertNull(lastChainedGroup.get(), "dangling nextGroupId must not crash or fire");
    }

    @Test
    void unregisteredPlayerIsNoop() {
        mgr.onMobDied(PLAYER, "a");
        mgr.incrementLive(PLAYER, "a");
        assertEquals(0, mgr.getLiveCount(PLAYER, "a"));
        assertNull(lastChainedGroup.get());
    }

}
