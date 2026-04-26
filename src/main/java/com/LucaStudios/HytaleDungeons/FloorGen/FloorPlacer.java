package com.LucaStudios.HytaleDungeons.FloorGen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.function.Consumer;

/**
 * Places and clears dungeon floors in the Hytale world using QuarterFloorBuilder.
 * Each floor is a single open arena split into 4 quarters at ascending heights.
 */
public final class FloorPlacer {

    private static final String FLOOR_BLOCK = "Rock_Stone_Brick";
    private static final String WALL_BLOCK = "Rock_Stone_Brick";
    private static final String STAIR_BLOCK = "Rock_Stone_Brick";
    private static final int DEFAULT_WIDTH = 40;
    private static final int DEFAULT_DEPTH = 40;

    private final Consumer<String> logger;

    public FloorPlacer() {
        this.logger = msg -> {};
    }

    /**
     * Place a floor using QuarterFloorBuilder — a single open arena
     * split into 4 quarters at ascending heights with stairs.
     * Must be called from the World thread (inside world.execute()).
     *
     * @param floor the floor data with room positions
     * @param world the world to place blocks in
     */
//    public void placeFloor(FloorData floor, World world) {
//        QuarterFloorBuilder.BlockPlacer blockPlacer = (x, y, z, blockType) ->
//                world.setBlock(x, y, z, blockType);
//
//        QuarterFloorBuilder.buildFloorPlan(
//                blockPlacer,
//                floor.getOriginX(), floor.getOriginY(), floor.getOriginZ(),
//                DEFAULT_WIDTH, DEFAULT_DEPTH,
//                FLOOR_BLOCK, STAIR_BLOCK, WALL_BLOCK
//        );
//
//        logger.accept("Placed quarter-floor for floor " + floor.getFloorNumber()
//                + " at (" + floor.getOriginX() + ", " + floor.getOriginY() + ", " + floor.getOriginZ() + ")"
//                + " size " + DEFAULT_WIDTH + "x" + DEFAULT_DEPTH);
//    }

    /**
     * Clear all blocks in the floor's bounding area (fill with air).
     * Must be called from the World thread.
     */
//    public void clearFloor(FloorData floor, World world) {
//        int x1 = floor.getOriginX();
//        int y1 = floor.getOriginY();
//        int z1 = floor.getOriginZ();
//        // Clear the quarter-floor area (+ a few extra Y for stairs/walls)
//        clearBlocks(world, x1, y1, z1, x1 + DEFAULT_WIDTH, y1 + 6, z1 + DEFAULT_DEPTH);
//        logger.accept("Cleared floor " + floor.getFloorNumber() + " area");
//    }

    /**
     * Teleport a player to a world position using Hytale's Teleport component.
     * This is processed by TeleportSystems which handles client sync and chunk loading.
     * Must be called from the World thread.
     */
    public void teleportPlayer(PlayerRef playerRef, float x, float y, float z) {
        if (!playerRef.isValid()) return;

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;

        Store<EntityStore> store = entityRef.getStore();

        Teleport teleport = Teleport.createForPlayer(
                new Vector3d(x, y, z),
                new Vector3f(0f, 0f, 0f)
        );
        store.addComponent(entityRef, Teleport.getComponentType(), teleport);

        logger.accept(String.format("Teleported player to (%f, %f, %f)", x, y, z));
    }

    /**
     * Fill a rectangular volume with a block type.
     */
    private void fillBlocks(World world, int x1, int y1, int z1, int x2, int y2, int z2, String blockId) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    world.setBlock(x, y, z, blockId);
                }
            }
        }
    }

    /**
     * Clear a rectangular volume by breaking all blocks.
     */
    private void clearBlocks(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    world.breakBlock(x, y, z, 0);
                }
            }
        }
    }
}
