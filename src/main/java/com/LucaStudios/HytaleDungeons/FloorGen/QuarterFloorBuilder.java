package com.LucaStudios.HytaleDungeons.FloorGen;

public class QuarterFloorBuilder {

    public interface BlockPlacer {
        void placeBlock(int x, int y, int z, String blockType);
    }

    public static class Rect {
        int x1, x2, z1, z2, y;

        public Rect(int x1, int x2, int z1, int z2, int y) {
            this.x1 = x1;
            this.x2 = x2;
            this.z1 = z1;
            this.z2 = z2;
            this.y = y;
        }

        public int centerX() {
            return (x1 + x2) / 2;
        }

        public int centerZ() {
            return (z1 + z2) / 2;
        }
    }

    public static void buildFloorPlan(
            BlockPlacer placer,
            int originX, int originY, int originZ,
            int width, int depth,
            String floorBlock,
            String stairBlock,
            String wallBlock
    ) {
        if (width < 6 || depth < 6) {
            throw new IllegalArgumentException("Width and depth must be at least 6");
        }

        int halfW = width / 2;
        int halfD = depth / 2;

        // Heights:
        // Q1 = lowest
        // Q2 = +1
        // Q3 = +2
        // Q4 = +3
        Rect q1 = new Rect(
                originX, originX + halfW - 1,
                originZ, originZ + halfD - 1,
                originY
        );

        Rect q2 = new Rect(
                originX + halfW, originX + width - 1,
                originZ, originZ + halfD - 1,
                originY + 1
        );

        Rect q3 = new Rect(
                originX + halfW, originX + width - 1,
                originZ + halfD, originZ + depth - 1,
                originY + 2
        );

        Rect q4 = new Rect(
                originX, originX + halfW - 1,
                originZ + halfD, originZ + depth - 1,
                originY + 3
        );

        // 1. Fill quarter floors
        fillRect(placer, q1, floorBlock);
        fillRect(placer, q2, floorBlock);
        fillRect(placer, q3, floorBlock);
        fillRect(placer, q4, floorBlock);

        // 2. Build stairs on selected borders:
        // Q1 <-> Q2
        // Q2 <-> Q3
        // Q3 <-> Q4
        buildVerticalBorderStair(placer, q1, q2, 3, stairBlock, floorBlock);
        buildHorizontalBorderStair(placer, q2, q3, 3, stairBlock, floorBlock);
        buildVerticalBorderStair(placer, q4, q3, 3, stairBlock, floorBlock);

        // 3. Build 2-block wall on the remaining border, Q1 <-> Q4
        buildHorizontalBorderWall(placer, q1, q4, wallBlock);
    }

    private static void fillRect(BlockPlacer placer, Rect rect, String blockType) {
        for (int x = rect.x1; x <= rect.x2; x++) {
            for (int z = rect.z1; z <= rect.z2; z++) {
                placer.placeBlock(x, rect.y, z, blockType);
            }
        }
    }

    /**
     * Stair across a vertical border:
     * left quarter touches right quarter
     */
    private static void buildVerticalBorderStair(
            BlockPlacer placer,
            Rect left,
            Rect right,
            int stairWidth,
            String stairBlock,
            String floorBlock
    ) {
        Rect low = left.y <= right.y ? left : right;
        Rect high = left.y > right.y ? left : right;

        int diff = high.y - low.y;
        if (diff <= 0) return;

        int borderX = left.x2 + 1 == right.x1 ? right.x1 : left.x1;
        int centerZ = Math.max(left.z1, right.z1) + (Math.min(left.z2, right.z2) - Math.max(left.z1, right.z1)) / 2;

        int half = stairWidth / 2;
        int zStart = centerZ - half;
        int zEnd = centerZ + half;

        for (int z = zStart; z <= zEnd; z++) {
            for (int step = 1; step <= diff; step++) {
                int y = low.y + step;

                if (low == left) {
                    int x = left.x2 + step;
                    placer.placeBlock(x, y, z, stairBlock);
                } else {
                    int x = right.x2 + step;
                    placer.placeBlock(x, y, z, stairBlock);
                }
            }
        }

        // Blend edges with floor blocks at the staircase entrance
        for (int z = zStart; z <= zEnd; z++) {
            placer.placeBlock(left.x2, left.y, z, floorBlock);
            placer.placeBlock(right.x1, right.y, z, floorBlock);
        }
    }

    /**
     * Stair across a horizontal border:
     * top quarter touches bottom quarter
     */
    private static void buildHorizontalBorderStair(
            BlockPlacer placer,
            Rect top,
            Rect bottom,
            int stairWidth,
            String stairBlock,
            String floorBlock
    ) {
        Rect low = top.y <= bottom.y ? top : bottom;
        Rect high = top.y > bottom.y ? top : bottom;

        int diff = high.y - low.y;
        if (diff <= 0) return;

        int borderZ = top.z2 + 1 == bottom.z1 ? bottom.z1 : top.z1;
        int centerX = Math.max(top.x1, bottom.x1) + (Math.min(top.x2, bottom.x2) - Math.max(top.x1, bottom.x1)) / 2;

        int half = stairWidth / 2;
        int xStart = centerX - half;
        int xEnd = centerX + half;

        for (int x = xStart; x <= xEnd; x++) {
            for (int step = 1; step <= diff; step++) {
                int y = low.y + step;

                if (low == top) {
                    int z = top.z2 + step;
                    placer.placeBlock(x, y, z, stairBlock);
                } else {
                    int z = bottom.z2 + step;
                    placer.placeBlock(x, y, z, stairBlock);
                }
            }
        }

        // Blend edges with floor blocks at the staircase entrance
        for (int x = xStart; x <= xEnd; x++) {
            placer.placeBlock(x, top.y, top.z2, floorBlock);
            placer.placeBlock(x, bottom.y, bottom.z1, floorBlock);
        }
    }

    /**
     * 2-block-high wall across a horizontal border:
     * top quarter touches bottom quarter
     */
    private static void buildHorizontalBorderWall(
            BlockPlacer placer,
            Rect top,
            Rect bottom,
            String wallBlock
    ) {
        int startX = Math.max(top.x1, bottom.x1);
        int endX = Math.min(top.x2, bottom.x2);

        int wallZ = top.z2; // build on top side edge
        int baseY = Math.max(top.y, bottom.y) + 1;

        for (int x = startX; x <= endX; x++) {
            placer.placeBlock(x, baseY, wallZ, wallBlock);
            placer.placeBlock(x, baseY + 1, wallZ, wallBlock);
        }
    }


}