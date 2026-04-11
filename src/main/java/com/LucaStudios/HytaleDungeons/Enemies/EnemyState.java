package com.LucaStudios.HytaleDungeons.Enemies;

import java.util.UUID;

/**
 * Identity fields tracked per spawned mob. HP is stored on the entity's native
 * {@code EntityStatMap} (health stat), so the server's damage/death pipeline
 * handles numbers, hit FX, and death animation for free. This state object
 * only carries the bits the native system doesn't know about: which archetype
 * rolled, the rolled base attack (used on mob→player hits), and the owning
 * player / spawn group.
 */
public final class EnemyState {

    public final Archetype archetype;
    public final int baseAtk;
    public final UUID playerId;
    public final String groupId;

    public EnemyState(Archetype archetype, int baseAtk, UUID playerId, String groupId) {
        this.archetype = archetype;
        this.baseAtk = baseAtk;
        this.playerId = playerId;
        this.groupId = groupId;
    }
}
