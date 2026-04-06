package com.LucaStudios.HytaleDungeons.FloorGen;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private int nextPlayerIndex = 0;

    private final Consumer<String> logger;
    private final FloorPlacer placer;

    public FloorGenerator(Consumer<String> logger) {
        this.logger = logger;
        this.placer = new FloorPlacer(logger);
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
        cleanupFloor(playerId, world);

        // Assign a player index for X-offset (stable per session)
        playerIndices.putIfAbsent(playerId, nextPlayerIndex++);
        int playerIndex = playerIndices.get(playerId);
        int originX = playerIndex * FLOOR_X_SPACING;

        // Build the floor layout
        FloorData floor = buildFloor(floorNumber, originX, FLOOR_Y);
        activeFloors.put(playerId, floor);

        logger.accept(String.format(
                "Floor %d generated for %s: %d rooms, %d mob spawns, origin=(%d, %d, %d)",
                floorNumber, playerId, floor.getRooms().size(), floor.getMobSpawnCount(),
                floor.getOriginX(), floor.getOriginY(), floor.getOriginZ()));

        // Place blocks in world
        if (world != null) {
            //placer.placeFloor(floor, world);
        }

        // Teleport player to spawn point
        if (playerRef != null && world != null) {
            int[] sp = floor.getPlayerSpawnPoint();
            placer.teleportPlayer(playerRef, sp[0], sp[1], sp[2]);
        }

        // TODO: Spawn mobs at floor.getAllMobSpawns() positions

        if (onReady != null) {
            onReady.run();
        }
    }

    /**
     * Clean up all floor blocks and mobs for a player (data only, no world changes).
     */
    public void cleanupFloor(UUID playerId) {
        cleanupFloor(playerId, null);
    }

    /**
     * Clean up all floor blocks and mobs for a player.
     *
     * @param world the world to clear blocks in (null to skip block clearing)
     */
    public void cleanupFloor(UUID playerId, World world) {
        FloorData old = activeFloors.remove(playerId);
        if (old != null) {
            if (world != null) {
                placer.clearFloor(old, world);
            }
            // TODO: Despawn any remaining mobs
            logger.accept("Cleaned up floor " + old.getFloorNumber() + " for " + playerId);
        }
    }

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
        cleanupFloor(playerId);
        playerIndices.remove(playerId);
    }

    /**
     * Get the active floor data for a player (for exit zone checking, etc.).
     */
    public FloorData getActiveFloor(UUID playerId) {
        return activeFloors.get(playerId);
    }

    // ── Generation Logic ────────────────────────────────────────────────

    /**
     * Build the floor layout: select rooms, calculate positions, assemble FloorData.
     * This is pure logic — no Hytale API calls.
     */
    FloorData buildFloor(int floorNumber, int originX, int originY) {
        RoomTemplateLibrary library = RoomTemplateLibrary.getInstance();
        Random random = new Random();

        int roomCount = MIN_ROOMS + random.nextInt(MAX_ROOMS - MIN_ROOMS + 1);
        int combatCount = roomCount - 2;

        // Select templates
        List<RoomTemplate> selectedTemplates = new ArrayList<>();
        selectedTemplates.add(library.randomSpawnRoom(random));
        for (int i = 0; i < combatCount; i++) {
            selectedTemplates.add(library.randomCombatRoom(random, floorNumber));
        }
        selectedTemplates.add(library.randomExitRoom(random));

        // Place rooms along the Z axis
        List<PlacedRoom> placedRooms = new ArrayList<>();
        int currentZ = 0;
        int maxSizeX = 0;
        int maxSizeY = 0;

        for (RoomTemplate template : selectedTemplates) {
            placedRooms.add(new PlacedRoom(template, originX, originY, currentZ));
            currentZ += template.sizeZ() + CORRIDOR_LENGTH;
            maxSizeX = Math.max(maxSizeX, template.sizeX());
            maxSizeY = Math.max(maxSizeY, template.sizeY());
        }

        // Total bounds (last room doesn't have a corridor after it)
        int totalZ = currentZ - CORRIDOR_LENGTH;

        return new FloorData(floorNumber, placedRooms,
                originX, originY, 0,
                maxSizeX, maxSizeY, totalZ);
    }
}
