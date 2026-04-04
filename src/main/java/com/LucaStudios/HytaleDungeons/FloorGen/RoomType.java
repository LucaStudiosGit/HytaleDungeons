package com.LucaStudios.HytaleDungeons.FloorGen;

/**
 * Room types that can appear in a dungeon floor.
 */
public enum RoomType {
    SPAWN,
    COMBAT,
    EXIT;

    public static RoomType fromString(String s) {
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return COMBAT;
        }
    }
}
