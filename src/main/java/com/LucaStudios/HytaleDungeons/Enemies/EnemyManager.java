package com.LucaStudios.HytaleDungeons.Enemies;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Spawns enemies for a {@link SpawnGroup} using Hytale's NPC API.
 *
 * <p>For now every mob spawns as the {@code Goblin_Hermit} role; archetype rolling
 * is performed and logged so the variance logic from the Enemy AI GDD is exercised,
 * but Hytale stat overrides are not yet wired (TODO).</p>
 */
public final class EnemyManager {

    public static final String NPC_ROLE = "Goblin_Hermit";

    public static final double HP_VARIANCE = 0.30;
    public static final double ATK_VARIANCE = 0.25;

    private final Consumer<String> logger;
    private final Random random = new Random();

    public EnemyManager(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Spawn every mob defined in the given group at its world-space position.
     * Must be called from the WorldThread.
     */
    public void spawnGroup(PlayerRef playerRef, World world, SpawnGroup group) {
        if (playerRef == null || world == null || group == null) return;
        if (!playerRef.isValid()) return;

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();
        if (store == null) return;

        int spawned = 0;
        int requested = group.totalMobCount();
        for (SpawnZone zone : group.zones()) {
            for (int i = 0; i < zone.count(); i++) {
                Archetype archetype = rollArchetype();
                int hp = rollWithVariance(archetype.baseHp, HP_VARIANCE);
                int atk = rollWithVariance(archetype.baseAtk, ATK_VARIANCE);

                double px = randomBetween(zone.x0(), zone.x1());
                double py = randomBetween(zone.y0(), zone.y1());
                double pz = randomBetween(zone.z0(), zone.z1());

                Vector3d position = new Vector3d(px, py, pz);
                Vector3f rotation = new Vector3f(0f, 0f, 0f);

                try {
                    NPCPlugin.get().spawnNPC(store, NPC_ROLE, null, position, rotation);
                    spawned++;
                    logger.accept(String.format(
                            "Spawned %s [%s] hp=%d atk=%d at (%.1f, %.1f, %.1f)",
                            NPC_ROLE, archetype.name(), hp, atk, px, py, pz));
                    // TODO: Apply hp/atk via EntityStatMap once stat-override path is proven
                } catch (Throwable t) {
                    logger.accept("Failed to spawn " + NPC_ROLE + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }

        logger.accept("SpawnGroup " + group.id() + " spawned " + spawned + "/" + requested + " mobs");
        // TODO: Track live mobs and chain nextGroup on clear (Enemy AI GDD)
    }

    private Archetype rollArchetype() {
        double r = random.nextDouble();
        double cum = 0;
        for (Archetype a : Archetype.values()) {
            cum += a.weight;
            if (r < cum) return a;
        }
        return Archetype.MELEE;
    }

    private double randomBetween(float a, float b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return lo + random.nextDouble() * (hi - lo);
    }

    private int rollWithVariance(int base, double variance) {
        double mult = 1.0 + (random.nextDouble() * 2.0 - 1.0) * variance;
        return Math.max(1, (int) Math.round(base * mult));
    }
}
