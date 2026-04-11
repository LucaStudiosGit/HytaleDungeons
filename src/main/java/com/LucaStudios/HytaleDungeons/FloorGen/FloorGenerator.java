package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.EnemyManager;
import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.math.vector.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Generates dungeon floors by assembling room templates into a linear chain.
 * Each floor: spawn room → combat rooms → exit room, connected by corridors.
 *
 * <p>Block placement and mob spawning are delegated to Hytale API calls that
 * will be wired when the API is proven. The generation logic (room selection,
 * positioning, data assembly) is engine-independent and fully testable.</p>
 */
public final class FloorGenerator {

    // ── Tuning Knobs (from GDD) ─────────────────────────────────────────

    public static final int MIN_ROOMS = 3;
    public static final int MAX_ROOMS = 5;
    public static final int CORRIDOR_LENGTH = 8;
    public static final int CORRIDOR_WIDTH = 3;
    public static final int CORRIDOR_HEIGHT = 4;
    public static final int FLOOR_Y = 200;
    public static final int FLOOR_X_SPACING = 200;

    private final ConcurrentHashMap<UUID, FloorData> activeFloors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> playerIndices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ZPlaneWatch> zPlaneWatches = new ConcurrentHashMap<>();
    private int nextPlayerIndex = 0;

    private final Consumer<String> logger;
    private final FloorPlacer placer;
    private final EnemyManager enemyManager;
    private final ScheduledExecutorService triggerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "floor-trigger-watcher");
        t.setDaemon(true);
        return t;
    });

    public FloorGenerator(EnemyManager enemyManager, Consumer<String> logger) {
        this.logger = logger;
        this.placer = new FloorPlacer(logger);
        this.enemyManager = enemyManager;
        triggerScheduler.scheduleAtFixedRate(this::pollZPlaneTriggers, 200, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * Per-player state tracking the first-crossing of an xPlane spawn trigger.
     */
    private static final class ZPlaneWatch {
        final PlayerRef playerRef;
        final World world;
        final int zPlane;
        Double prevZ;
        boolean fired;

        ZPlaneWatch(PlayerRef playerRef, World world, int zPlane) {
            this.playerRef = playerRef;
            this.world = world;
            this.zPlane = zPlane;
        }
    }

    private void pollZPlaneTriggers() {
        for (var entry : zPlaneWatches.entrySet()) {
            UUID playerId = entry.getKey();
            ZPlaneWatch watch = entry.getValue();
            if (watch.fired || watch.world == null || watch.playerRef == null) continue;
            watch.world.execute(() -> checkZPlaneCrossing(playerId, watch));
        }
    }

    private void checkZPlaneCrossing(UUID playerId, ZPlaneWatch watch) {
        if (watch.fired) return;
        if (!watch.playerRef.isValid()) {
            zPlaneWatches.remove(playerId);
            return;
        }
        var transform = watch.playerRef.getTransform();
        if (transform == null) return;
        Vector3d pos = transform.getPosition();
        if (pos == null) return;
        double currZ = pos.z;
        if (watch.prevZ == null) {
            watch.prevZ = currZ;
            return;
        }
        double prev = watch.prevZ;
        double plane = watch.zPlane;
        double low = plane - 0.5;
        double high = plane + 0.5;
        boolean inBand = currZ >= low && currZ <= high;
        boolean jumpedOver = (prev < low && currZ > high) || (prev > high && currZ < low);
        boolean crossed = inBand || jumpedOver;
        watch.prevZ = currZ;
        if (crossed) {
            watch.fired = true;
            onZPlaneTriggerFired(playerId);
        }
    }

    private void onZPlaneTriggerFired(UUID playerId) {
        FloorData floor = activeFloors.get(playerId);
        if (floor == null) return;
        ZPlaneWatch watch = zPlaneWatches.get(playerId);
        if (watch == null) return;
        SpawnGroup group = floor.findFirstZoneGroup();
        if (group == null) {
            logger.accept("zPlane trigger fired but no zone spawn group on floor " + floor.getFloorNumber());
            return;
        }
        logger.accept("zPlane trigger fired for floor " + floor.getFloorNumber()
                + " — spawning group " + group.id());
        enemyManager.spawnGroup(watch.playerRef, watch.world, group);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Generate a new floor for a player. Cleans up any existing floor first.
     * Calls onReady when generation is complete.
     * This overload skips block placement (data-only, for testing).
     *
     * @param playerId the player
     * @param floorNumber the floor to generate (1-based)
     * @param onReady callback invoked when the floor is ready to play
     */
    public void generateFloor(UUID playerId, int floorNumber, Runnable onReady) {
        generateFloor(playerId, floorNumber, null, null, onReady);
    }

    /**
     * Generate a new floor for a player with block placement and teleportation.
     * Cleans up any existing floor first. Calls onReady when generation is complete.
     *
     * @param playerId the player
     * @param floorNumber the floor to generate (1-based)
     * @param world the world to place blocks in (null to skip placement)
     * @param playerRef the player to teleport (null to skip teleport)
     * @param onReady callback invoked when the floor is ready to play
     */
    public void generateFloor(UUID playerId, int floorNumber, World world, PlayerRef playerRef, Runnable onReady) {
        // Clean up old floor if any
//        cleanupFloor(playerId, world);

        // Remove any mobs left over from the previous floor — including NPCs that
        // survived a server restart and aren't in our per-player tracking.
        enemyManager.cleanAllEntities();

        // Assign a player index for X-offset (stable per session)
        playerIndices.putIfAbsent(playerId, nextPlayerIndex++);
        int playerIndex = playerIndices.get(playerId);
        int originX = playerIndex * FLOOR_X_SPACING;

        // Build the floor layout
        FloorData floor = buildFloor(floorNumber, originX, FLOOR_Y);
        activeFloors.put(playerId, floor);

        // Arm the zPlane spawn trigger from the first zone-triggered spawn group
        SpawnGroup zoneGroup = floor.findFirstZoneGroup();
        if (zoneGroup != null && zoneGroup.zPlane() != 0 && playerRef != null) {
            zPlaneWatches.put(playerId, new ZPlaneWatch(playerRef, world, zoneGroup.zPlane()));
        } else {
            zPlaneWatches.remove(playerId);
        }

        // Register all spawn groups for chain (on_cleared) lookups + live-count tracking.
        enemyManager.registerFloor(playerId, playerRef, world, floor.getSpawnGroups());

//        logger.accept(String.format(
//                "Floor %d generated for %s: %d rooms, %d mob spawns, origin=(%d, %d, %d)",
//                floorNumber, playerId, floor.getRooms().size(), floor.getMobSpawnCount(),
//                floor.getOriginX(), floor.getOriginY(), floor.getOriginZ()));

        logger.accept("Floor " + floor.getFloorNumber() + " built for " + playerId);

        // World-thread operations: block placement, teleport, callback
        if (world != null) {
            world.execute(() -> {
                // Place blocks in world (currently disabled)
                // placer.placeFloor(floor, world);

                //placeTrigger
                

                // Teleport player to spawn point
                if (playerRef != null) {
                    float[] spawnPoint = floor.getPlayerSpawnPoint();
                    placer.teleportPlayer(playerRef, spawnPoint[0], spawnPoint[1], spawnPoint[2]);
                }

                // TODO: Spawn mobs at floor.getAllMobSpawns() positions

                if (onReady != null) {
                    onReady.run();
                }
            });
        } else {
            if (onReady != null) {
                onReady.run();
            }
        }
    }

//    /**
//     * Clean up all floor blocks and mobs for a player (data only, no world changes).
//     */
//    public void cleanupFloor(UUID playerId) {
//        cleanupFloor(playerId, null);
//    }

    /**
     * Clean up all floor blocks and mobs for a player.
     *
     * @param world the world to clear blocks in (null to skip block clearing)
     */
//    public void cleanupFloor(UUID playerId, World world) {
//        FloorData old = activeFloors.remove(playerId);
//        if (old != null) {
//            if (world != null) {
//                placer.clearFloor(old, world);
//            }
//            // TODO: Despawn any remaining mobs
//            logger.accept("Cleaned up floor " + old.getFloorNumber() + " for " + playerId);
//        }
//    }

    /**
     * Reset mobs to original spawn positions (same floor layout).
     * Used when player respawns after death.
     *
     * @return the new mob count, or -1 if no floor exists
     */
    public int resetMobs(UUID playerId) {
        FloorData floor = activeFloors.get(playerId);
        if (floor == null) return -1;

        // TODO: Despawn surviving mobs
        // TODO: Re-spawn mobs at floor.getAllMobSpawns() positions

        logger.accept("Reset " + floor.getMobSpawnCount() + " mob spawns for " + playerId
                + " on floor " + floor.getFloorNumber());
        return floor.getMobSpawnCount();
    }

    /**
     * Remove all data for a disconnected player.
     */
    public void removePlayer(UUID playerId) {
//        cleanupFloor(playerId);
        playerIndices.remove(playerId);
        zPlaneWatches.remove(playerId);
    }

    /**
     * Get the active floor data for a player (for exit zone checking, etc.).
     */
    public FloorData getActiveFloor(UUID playerId) {
        return activeFloors.get(playerId);
    }

    /**
     * Teleport a player to the spawn point of their currently active floor.
     * No-op if the player has no active floor or the ref is invalid.
     * Must be called from the world thread.
     */
    public void teleportToActiveFloorSpawn(UUID playerId, PlayerRef playerRef) {
        FloorData floor = activeFloors.get(playerId);
        if (floor == null || playerRef == null || !playerRef.isValid()) return;
        float[] sp = floor.getPlayerSpawnPoint();
        if (sp == null || sp.length < 3) return;
        placer.teleportPlayer(playerRef, sp[0], sp[1], sp[2]);
    }

    // ── Generation Logic ────────────────────────────────────────────────

    /**
     * Build the floor layout: select rooms, calculate positions, assemble FloorData.
     * This is pure logic — no Hytale API calls.
     */
    FloorData buildFloor(int floorNumber, int originX, int originY) {
        FloorTemplateLibrary library = FloorTemplateLibrary.getInstance();
        FloorTemplate template = library.getTemplate(floorNumber);
        return new FloorData(
                floorNumber,
                new float[]{template.spawnPointX(), template.spawnPointY(), template.spawnPointZ()},
                template.spawnGroups());
    }
}
