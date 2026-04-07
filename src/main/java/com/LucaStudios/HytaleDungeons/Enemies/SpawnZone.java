package com.LucaStudios.HytaleDungeons.Enemies;

/**
 * An axis-aligned box inside which {@code count} mobs are spawned at random points.
 * Coordinates are world-space; the two corners may be given in any order.
 */
public record SpawnZone(
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        int count
) {
}
