package com.LucaStudios.HytaleDungeons.Enemies;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnemyStateTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void storesIdentityFields() {
        EnemyState s = new EnemyState(Archetype.MELEE, 10, PLAYER, "group_a");
        assertEquals(Archetype.MELEE, s.archetype());
        assertEquals(10, s.baseAtk());
        assertEquals(PLAYER, s.playerId());
        assertEquals("group_a", s.groupId());
    }
}
