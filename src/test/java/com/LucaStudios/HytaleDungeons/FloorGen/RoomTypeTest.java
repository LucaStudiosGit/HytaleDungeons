package com.LucaStudios.HytaleDungeons.FloorGen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomTypeTest {

    @Test
    void fromString_validTypes() {
        assertEquals(RoomType.SPAWN, RoomType.fromString("spawn"));
        assertEquals(RoomType.COMBAT, RoomType.fromString("combat"));
        assertEquals(RoomType.EXIT, RoomType.fromString("exit"));
    }

    @Test
    void fromString_caseInsensitive() {
        assertEquals(RoomType.SPAWN, RoomType.fromString("SPAWN"));
        assertEquals(RoomType.COMBAT, RoomType.fromString("Combat"));
    }

    @Test
    void fromString_unknownDefaultsToCombat() {
        assertEquals(RoomType.COMBAT, RoomType.fromString("unknown"));
        assertEquals(RoomType.COMBAT, RoomType.fromString("boss"));
    }
}
