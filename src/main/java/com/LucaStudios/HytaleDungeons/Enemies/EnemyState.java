package com.LucaStudios.HytaleDungeons.Enemies;

import java.util.UUID;

public record EnemyState(
        Archetype archetype,
        int baseAtk,
        UUID playerId,
        String groupId) {
}
