package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;
import com.LucaStudios.HytaleDungeons.Enemies.SpawnZone;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.math.vector.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class FloorGenerator {

    public static final int FLOOR_Y = 200;
    public static final int FLOOR_X_SPACING = 200;

    private static final double TELEPORT_DELTA_THRESHOLD = 4.0;
    private static final double FALL_REARM_MARGIN = 5.0;

    // Keyed by partyId (== playerId for solo)
    private final ConcurrentHashMap<UUID, FloorData> activeFloors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> partyIndices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ZPlaneWatch> zPlaneWatches = new ConcurrentHashMap<>();
    // Keyed by playerId — each player has their own fall detection
    private final ConcurrentHashMap<UUID, FallWatch> fallWatches = new ConcurrentHashMap<>();
    private BiConsumer<UUID, PlayerRef> onPlayerFell;
    private int nextPartyIndex = 0;

    private final FloorPlacer placer;
    private final EnemyManager enemyManager;

    public FloorGenerator(EnemyManager enemyManager) {
        this.placer = new FloorPlacer();
        this.enemyManager = enemyManager;
        ScheduledExecutorService triggerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "floor-trigger-watcher");
            t.setDaemon(true);
            return t;
        });
        triggerScheduler.scheduleAtFixedRate(this::pollZPlaneTriggers, 200, 200, TimeUnit.MILLISECONDS);
        triggerScheduler.scheduleAtFixedRate(this::pollFallTriggers, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void setOnPlayerFell(BiConsumer<UUID, PlayerRef> callback) {
        this.onPlayerFell = callback;
    }

    // ── Trigger watches ─────────────────────────────────────────────────

    private static final class ZPlaneWatch {
        final Set<PlayerRef> playerRefs;
        final World world;
        final int zPlane;
        final UUID partyId;
        final ConcurrentHashMap<UUID, Double> prevZByPlayer = new ConcurrentHashMap<>();
        volatile boolean fired;

        ZPlaneWatch(Set<PlayerRef> playerRefs, World world, int zPlane, UUID partyId) {
            this.playerRefs = playerRefs;
            this.world = world;
            this.zPlane = zPlane;
            this.partyId = partyId;
        }
    }

    private void pollZPlaneTriggers() {
        for (var entry : zPlaneWatches.entrySet()) {
            ZPlaneWatch watch = entry.getValue();
            if (watch.fired || watch.world == null) continue;
            for (PlayerRef pr : watch.playerRefs) {
                if (pr != null && pr.isValid()) {
                    watch.world.execute(() -> checkZPlaneCrossing(watch, pr));
                }
            }
        }
    }

    private void checkZPlaneCrossing(ZPlaneWatch watch, PlayerRef playerRef) {
        if (watch.fired) return;
        if (!playerRef.isValid()) return;

        UUID playerId = playerRef.getUuid();
        var transform = playerRef.getTransform();
        Vector3d pos = transform.getPosition();
        double currZ = pos.z;

        Double prev = watch.prevZByPlayer.put(playerId, currZ);
        if (prev == null) return;

        double plane = watch.zPlane;
        double low = plane - 0.5;
        double high = plane + 0.5;

        if (Math.abs(currZ - prev) > TELEPORT_DELTA_THRESHOLD) return;

        boolean inBand = currZ >= low && currZ <= high;
        boolean jumpedOver = (prev < low && currZ > high) || (prev > high && currZ < low);
        if (inBand || jumpedOver) {
            watch.fired = true;
            onZPlaneTriggerFired(watch.partyId);
        }
    }

    private static final class FallWatch {
        final PlayerRef playerRef;
        final World world;
        final int fallY;
        final UUID partyId;
        boolean active = true;

        FallWatch(PlayerRef playerRef, World world, int fallY, UUID partyId) {
            this.playerRef = playerRef;
            this.world = world;
            this.fallY = fallY;
            this.partyId = partyId;
        }
    }

    private void pollFallTriggers() {
        for (var entry : fallWatches.entrySet()) {
            UUID playerId = entry.getKey();
            FallWatch watch = entry.getValue();
            if (watch.world == null || watch.playerRef == null) continue;
            watch.world.execute(() -> checkFall(playerId, watch));
        }
    }

    private void checkFall(UUID playerId, FallWatch watch) {
        if (!watch.playerRef.isValid()) {
            fallWatches.remove(playerId);
            return;
        }
        enemyManager.killFallenMobs(watch.partyId, watch.fallY);

        double y = watch.playerRef.getTransform().getPosition().y;
        if (!watch.active) {
            if (y > watch.fallY + FALL_REARM_MARGIN) {
                watch.active = true;
            }
            return;
        }
        if (y < watch.fallY) {
            watch.active = false;
            BiConsumer<UUID, PlayerRef> cb = onPlayerFell;
            if (cb != null) cb.accept(playerId, watch.playerRef);
        }
    }

    private void onZPlaneTriggerFired(UUID partyId) {
        FloorData floor = activeFloors.get(partyId);
        if (floor == null) return;
        ZPlaneWatch watch = zPlaneWatches.get(partyId);
        if (watch == null) return;
        SpawnGroup group = floor.findFirstZoneGroup();
        if (group == null) return;
        enemyManager.spawnGroup(partyId, watch.world, group);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Convenience wrapper for solo runs (partyId = playerId). */
    public void generateFloor(UUID playerId, int floorNumber, World world, PlayerRef playerRef, Runnable onReady) {
        generatePartyFloor(playerId, floorNumber, world, Map.of(playerId, playerRef), 1, onReady);
    }

    /**
     * Generate a shared floor for a party.
     *
     * @param partyId    canonical party UUID (== playerId for solo)
     * @param floorNumber which floor template to use
     * @param world      the world to generate into
     * @param playerRefs map of memberId → PlayerRef for all party members
     * @param partySize  number of players (used for enemy scaling)
     * @param onReady    callback fired when the floor is ready (on the world thread)
     */
    public void generatePartyFloor(UUID partyId, int floorNumber, World world,
                                   Map<UUID, PlayerRef> playerRefs, int partySize,
                                   Runnable onReady) {
        partyIndices.putIfAbsent(partyId, nextPartyIndex++);
        int partyIndex = partyIndices.get(partyId);

        FloorData rawFloor = buildFloor(floorNumber);
        FloorData floor = partySize > 1 ? scaleFloor(rawFloor, partySize) : rawFloor;
        activeFloors.put(partyId, floor);

        // Determine the representative PlayerRef (first valid one) for EnemyManager
        PlayerRef repRef = null;
        for (PlayerRef pr : playerRefs.values()) {
            if (pr != null && pr.isValid()) { repRef = pr; break; }
        }

        // Z-plane trigger — fires when any party member crosses the plane
        SpawnGroup zoneGroup = floor.findFirstZoneGroup();
        if (zoneGroup != null && zoneGroup.zPlane() != 0 && !playerRefs.isEmpty()) {
            Set<PlayerRef> refs = ConcurrentHashMap.newKeySet();
            refs.addAll(playerRefs.values());
            zPlaneWatches.put(partyId, new ZPlaneWatch(refs, world, zoneGroup.zPlane(), partyId));
        } else {
            zPlaneWatches.remove(partyId);
        }

        // Fall triggers — one per party member (each player's Y is tracked independently)
        for (Map.Entry<UUID, PlayerRef> e : playerRefs.entrySet()) {
            UUID memberId = e.getKey();
            PlayerRef memberRef = e.getValue();
            if (memberRef != null && memberRef.isValid() && world != null) {
                fallWatches.put(memberId, new FallWatch(memberRef, world, floor.fallY(), partyId));
            } else {
                fallWatches.remove(memberId);
            }
        }

        final PlayerRef finalRepRef = repRef;
        final FloorData finalFloor = floor;

        if (world != null) {
            world.execute(() -> {
                enemyManager.removeTrackedMobs();
                enemyManager.cleanAllEntities();
                enemyManager.registerFloor(partyId, finalRepRef, world, finalFloor.spawnGroups());

                // Teleport all party members to the spawn point (slight Z offset to avoid stacking)
                float[] sp = finalFloor.playerSpawnPoint();
                int idx = 0;
                for (PlayerRef pr : playerRefs.values()) {
                    if (pr != null && pr.isValid()) {
                        placer.teleportPlayer(pr, sp[0], sp[1], sp[2] + idx * 2.0f);
                        idx++;
                    }
                }

                if (onReady != null) onReady.run();
            });
        } else {
            enemyManager.cleanAllEntities();
            enemyManager.registerFloor(partyId, finalRepRef, world, finalFloor.spawnGroups());
            if (onReady != null) onReady.run();
        }
    }

    public void removePlayer(UUID playerId) {
        // Remove individual fall watch for this player
        fallWatches.remove(playerId);
        // If solo (partyId == playerId), also clean up the party-level entries
        partyIndices.remove(playerId);
        zPlaneWatches.remove(playerId);
        activeFloors.remove(playerId);
    }

    public FloorData getActiveFloor(UUID partyId) {
        return activeFloors.get(partyId);
    }

    public void teleportToActiveFloorSpawn(UUID partyId, PlayerRef playerRef) {
        FloorData floor = activeFloors.get(partyId);
        if (floor == null || playerRef == null || !playerRef.isValid()) return;
        float[] sp = floor.playerSpawnPoint();
        if (sp == null || sp.length < 3) return;
        placer.teleportPlayer(playerRef, sp[0], sp[1], sp[2]);
    }

    // ── Generation Logic ────────────────────────────────────────────────

    FloorData buildFloor(int floorNumber) {
        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        FloorTemplate template = library.getTemplate(floorNumber);
        return new FloorData(
                floorNumber,
                new float[]{template.spawnPointX(), template.spawnPointY(), template.spawnPointZ()},
                template.fallY(),
                template.spawnGroups());
    }

    /**
     * Scale mob counts in each SpawnZone by {@code 1 + 0.5 * (partySize - 1)}.
     * For 2 players → 1.5×, 3 players → 2×, 4 players → 2.5×.
     */
    private FloorData scaleFloor(FloorData raw, int partySize) {
        double multiplier = 1.0 + 0.5 * (partySize - 1);
        List<SpawnGroup> scaledGroups = new ArrayList<>();
        for (SpawnGroup g : raw.spawnGroups()) {
            List<SpawnZone> scaledZones = new ArrayList<>();
            for (SpawnZone z : g.zones()) {
                int newCount = Math.max(1, (int) Math.round(z.count() * multiplier));
                scaledZones.add(new SpawnZone(z.x0(), z.y0(), z.z0(), z.x1(), z.y1(), z.z1(), newCount));
            }
            scaledGroups.add(new SpawnGroup(g.id(), g.triggerType(), g.zPlane(), scaledZones, g.nextGroupId()));
        }
        return new FloorData(raw.floorNumber(), raw.playerSpawnPoint(), raw.fallY(), scaledGroups);
    }
}
