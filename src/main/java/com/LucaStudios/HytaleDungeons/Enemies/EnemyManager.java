package com.LucaStudios.HytaleDungeons.Enemies;

import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Spawns enemies for a {@link SpawnGroup} using Hytale's NPC API.
 *
 * <p>For now every mob spawns as the {@code Goblin_Hermit} role; archetype rolling
 * is performed and logged so the variance logic from the Enemy AI GDD is exercised,
 * but Hytale stat overrides are not yet wired (TODO).</p>
 */
public final class EnemyManager {

//    public static final String NPC_ROLE = "Goblin_Hermit";

    public static final double HP_VARIANCE = 0.15;
    public static final double ATK_VARIANCE = 0.10;

    /** Delay between consecutive mob spawns within a group, in milliseconds. */
    public static final long SPAWN_STAGGER_MS = 150L;

    /** Modifier key used to cap a mob's native max HP at our rolled value. */
    private static final String MAX_HP_MODIFIER_KEY = "hytaleDungeons:rolled_max";

    private final RunStateManager runStateManager;
    private final Consumer<String> logger;
    private final Random random = new Random();
    private final ScheduledExecutorService staggerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "enemy-spawn-stagger");
        t.setDaemon(true);
        return t;
    });

    /**
     * Primary per-entity state map. Keyed by the {@link Ref} returned from
     * {@code NPCPlugin.spawnNPC}. Populated on spawn, removed on onNativeDeath / cleanup.
     */
    private final Map<Ref<EntityStore>, EnemyState> states = new ConcurrentHashMap<>();
    /** Staggered spawns still waiting to fire per player — cancelled on despawnAll. */
    private final Map<UUID, List<ScheduledFuture<?>>> pendingByPlayer = new ConcurrentHashMap<>();
    /** Per-player floor registration (group lookup + live counts + spawn context). */
    private final Map<UUID, FloorRegistration> floors = new ConcurrentHashMap<>();
    /**
     * Per-player generation counter. Bumped on every {@link #cleanAllEntities()}
     * and {@link #despawnAll}. Stagger-scheduled spawns capture the generation at
     * schedule time and skip the spawn if it's stale — prevents orphan mobs when
     * a floor regen fires while stagger spawns are still in-flight.
     */
    private final ConcurrentHashMap<UUID, Integer> generationByPlayer = new ConcurrentHashMap<>();

    /**
     * Test seam for the chain-spawn dispatch. Invoked whenever a group's live count
     * reaches zero and the group has a {@code nextGroupId}. Default implementation
     * looks up the next group in the player's {@link FloorRegistration} and calls
     * {@link #spawnGroup}. Tests can override to capture chain triggers without
     * touching the NPC API.
     */
    BiConsumer<UUID, SpawnGroup> chainSpawner = this::defaultChainSpawn;

    /** One floor's worth of per-player state: the group registry, live counts, and spawn context. */
    static final class FloorRegistration {
        final PlayerRef playerRef;
        final World world;
        final Map<String, SpawnGroup> groupsById;
        final Map<String, Integer> liveByGroupId;

        FloorRegistration(PlayerRef playerRef, World world, List<SpawnGroup> groups) {
            this.playerRef = playerRef;
            this.world = world;
            this.groupsById = new HashMap<>();
            this.liveByGroupId = new HashMap<>();
            if (groups != null) {
                for (SpawnGroup g : groups) {
                    groupsById.put(g.id(), g);
                    liveByGroupId.put(g.id(), 0);
                }
            }
        }
    }

    public EnemyManager(RunStateManager runStateManager, Consumer<String> logger) {
        this.runStateManager = runStateManager;
        this.logger = logger;
    }

    /**
     * Register all spawn groups for a player's current floor. Must be called before
     * the first {@link #spawnGroup} for that floor so chain ({@code on_cleared})
     * lookups and live-count tracking work. Replaces any prior registration.
     */
    public void registerFloor(UUID playerId, PlayerRef playerRef, World world, List<SpawnGroup> groups) {
        if (playerId == null) return;
        floors.put(playerId, new FloorRegistration(playerRef, world, groups));
        logger.accept("Registered floor for " + playerId + " with "
                + (groups == null ? 0 : groups.size()) + " spawn groups");
    }

    /**
     * Test-only overload that registers a floor without a {@link PlayerRef} or
     * {@link World}. Chain dispatch still works via the {@link #chainSpawner}
     * seam; the default chain-spawner will no-op because the context is null.
     * Kept here (rather than in a test helper) so tests don't need Hytale API
     * classes on their compile classpath.
     */
    void registerFloorForTest(UUID playerId, List<SpawnGroup> groups) {
        if (playerId == null) return;
        floors.put(playerId, new FloorRegistration(null, null, groups));
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

        final UUID playerId = playerRef.getUuid();
        final String groupId = group.id();
        final int generation = generationByPlayer.getOrDefault(playerId, 0);
        List<ScheduledFuture<?>> pending = pendingByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>());
        int requested = group.totalMobCount();
        int index = 0;
        for (SpawnZone zone : group.zones()) {
            for (int i = 0; i < zone.count(); i++) {
                Archetype archetype = rollArchetype();
                int hp = rollWithVariance(archetype.baseHp, HP_VARIANCE);
                int atk = rollWithVariance(archetype.baseAtk, ATK_VARIANCE);

                double px = randomBetween(zone.x0(), zone.x1());
                double py = randomBetween(zone.y0(), zone.y1());
                double pz = randomBetween(zone.z0(), zone.z1());

                final Vector3d position = new Vector3d(px, py, pz);
                final Vector3f rotation = new Vector3f(0f, 0f, 0f);
                final Archetype arch = archetype;
                final int fHp = hp;
                final int fAtk = atk;
                final long delayMs = (long) index * SPAWN_STAGGER_MS;
                index++;

                ScheduledFuture<?> future = staggerScheduler.schedule(() -> world.execute(() -> {
                    if (!playerRef.isValid() || !entityRef.isValid()) return;
                    // Generation changed — a cleanup ran after this spawn was scheduled.
                    int curGen = generationByPlayer.getOrDefault(playerId, 0);
                    if (curGen != generation) return;
                    try {
                        Pair<Ref<EntityStore>, ?> spawned =
                                NPCPlugin.get().spawnNPC(store, arch.entityID, null, position, rotation);
                        if (spawned != null && spawned.left() != null) {
                            Ref<EntityStore> ref = spawned.left();
                            states.put(ref, new EnemyState(arch, fAtk, playerId, groupId));
                            incrementLive(playerId, groupId);
                            // Cap the mob's max HP at our rolled value via an ADDITIVE
                            // modifier so native regen can't restore it past the roll,
                            // then overwrite current HP so it starts at the rolled value.
                            // The native damage pipeline drains this down to zero and
                            // triggers death animation / kill feed / corpse removal.
                            EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
                            if (statMap != null) {
                                int healthIdx = DefaultEntityStatTypes.getHealth();
                                EntityStatValue healthStat = statMap.get(healthIdx);
                                if (healthStat != null) {
                                    float delta = (float) fHp - healthStat.getMax();
                                    StaticModifier maxCap = new StaticModifier(
                                            Modifier.ModifierTarget.MAX,
                                            StaticModifier.CalculationType.ADDITIVE,
                                            delta);
                                    statMap.putModifier(healthIdx, MAX_HP_MODIFIER_KEY, maxCap);
                                    statMap.setStatValue(healthIdx, (float) fHp);
                                }
                            }
                        }
                        logger.accept(String.format(
                                "Spawned %s [%s] hp=%d atk=%d group=%s at (%.1f, %.1f, %.1f)",
                                arch.entityID, arch.name(), fHp, fAtk, groupId,
                                position.x, position.y, position.z));
                        // TODO: play "pop"/"swish" SFX at position once Hytale audio API is identified
                    } catch (Throwable t) {
                        logger.accept("Failed to spawn " + arch.entityID + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }), delayMs, TimeUnit.MILLISECONDS);
                pending.add(future);
            }
        }

        logger.accept("SpawnGroup " + group.id() + " scheduling " + requested
                + " mobs (stagger " + SPAWN_STAGGER_MS + "ms)");
        // TODO: Track live mobs and chain nextGroup on clear (Enemy AI GDD)
    }

    /**
     * Pre-remove all tracked mobs with per-entity {@code isValid()} checks so
     * that the subsequent {@code /entity clean} nuke has fewer targets and is
     * less likely to crash on the parallel flock-membership race. Must be
     * called from the <strong>world thread</strong>.
     *
     * @return number of entities successfully removed
     */
    public int removeTrackedMobs() {
        int removed = 0;
        for (Map.Entry<Ref<EntityStore>, EnemyState> e : states.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            if (ref == null || !ref.isValid()) continue;
            try {
                Store<EntityStore> s = ref.getStore();
                if (s != null) {
                    s.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Throwable t) {
                // Entity already dead/removed by the native pipeline — safe to ignore.
            }
        }
        return removed;
    }

    /**
     * Cancel pending stagger spawns, clear tracking maps, and run Hytale's
     * {@code /entity clean --confirm} as a fallback nuke for mobs our tracking
     * doesn't know about (e.g. survived a server restart). Call
     * {@link #removeTrackedMobs()} on the world thread <em>before</em> this
     * method to avoid the parallel-removal race in the engine's clean command.
     */
    public void cleanAllEntities() {
        // Bump generation for every known player so in-flight stagger spawns
        // see the mismatch and skip.
        for (UUID pid : generationByPlayer.keySet()) {
            generationByPlayer.merge(pid, 1, Integer::sum);
        }

        int cancelled = 0;
        for (List<ScheduledFuture<?>> list : pendingByPlayer.values()) {
            for (ScheduledFuture<?> f : list) {
                if (f.cancel(false)) cancelled++;
            }
        }
        pendingByPlayer.clear();
        states.clear();
        floors.clear();

        try {
            CommandManager.get().handleCommand(ConsoleSender.INSTANCE, "entity clean --confirm");
            logger.accept("Ran /entity clean --confirm (cancelled " + cancelled + " pending spawns)");
        } catch (Throwable t) {
            logger.accept("Failed to run /entity clean: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Cancel any pending staggered spawns and remove all live mobs previously spawned
     * for this player. Safe to call from any thread — the actual entity removal is
     * marshalled onto the world thread.
     */
    public void despawnAll(UUID playerId, World world) {
        if (playerId == null) return;

        generationByPlayer.merge(playerId, 1, Integer::sum);

        List<ScheduledFuture<?>> pending = pendingByPlayer.remove(playerId);
        int cancelled = 0;
        if (pending != null) {
            for (ScheduledFuture<?> f : pending) {
                if (f.cancel(false)) cancelled++;
            }
        }

        List<Ref<EntityStore>> toRemove = new ArrayList<>();
        Iterator<Map.Entry<Ref<EntityStore>, EnemyState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, EnemyState> e = it.next();
            if (playerId.equals(e.getValue().playerId)) {
                toRemove.add(e.getKey());
                it.remove();
            }
        }
        // Forget the floor registration so chained spawns don't fire after cleanup.
        floors.remove(playerId);

        if (toRemove.isEmpty() && cancelled == 0) return;

        if (world == null || toRemove.isEmpty()) {
            logger.accept("Despawn for " + playerId + ": cancelled " + cancelled
                    + " pending, no live mobs to remove");
            return;
        }

        final int cancelledFinal = cancelled;
        world.execute(() -> {
            int removed = 0;
            for (Ref<EntityStore> ref : toRemove) {
                if (ref == null || !ref.isValid()) continue;
                try {
                    Store<EntityStore> s = ref.getStore();
                    if (s == null) continue;
                    s.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } catch (Throwable t) {
                    logger.accept("Failed to remove mob: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            logger.accept("Despawned " + removed + "/" + toRemove.size()
                    + " mobs for " + playerId + " (cancelled " + cancelledFinal + " pending)");
        });
    }

    // ── Damage / Death (Enemy AI GDD §3) ────────────────────────────────

    /** Look up the state for an entity, or {@code null} if not tracked. */
    public EnemyState getState(Ref<EntityStore> ref) {
        return (ref == null) ? null : states.get(ref);
    }

    /**
     * Called by {@link MobDeathObserver} when the native pipeline has added a
     * {@code DeathComponent} to one of our tracked mobs. Clears our state,
     * decrements the live count, fires chain spawns, and notifies
     * {@link RunStateManager#onMobKilled}. The native corpse removal system
     * handles the actual entity cleanup.
     */
    public void onNativeDeath(Ref<EntityStore> ref) {
        if (ref == null) return;
        EnemyState state = states.remove(ref);
        if (state == null) return;

        logger.accept(String.format("Killed mob [%s] group=%s for %s",
                state.archetype.name(), state.groupId, state.playerId));

        onMobDied(state.playerId, state.groupId);

        if (runStateManager != null) {
            runStateManager.onMobKilled(state.playerId);
        }
    }

    /**
     * Increment the live-mob counter for a (player, group). Called when a spawn
     * lands; also used by tests to seed state without touching the NPC API.
     */
    void incrementLive(UUID playerId, String groupId) {
        FloorRegistration reg = floors.get(playerId);
        if (reg == null || groupId == null) return;
        reg.liveByGroupId.merge(groupId, 1, Integer::sum);
    }

    /** Current live count for a (player, group), or 0 if unknown. */
    public int getLiveCount(UUID playerId, String groupId) {
        FloorRegistration reg = floors.get(playerId);
        if (reg == null || groupId == null) return 0;
        Integer n = reg.liveByGroupId.get(groupId);
        return n == null ? 0 : n;
    }

    /**
     * Decrement a group's live count. If it reaches 0 and the group has a
     * {@code nextGroupId}, dispatch the chain via {@link #chainSpawner}.
     */
    void onMobDied(UUID playerId, String groupId) {
        FloorRegistration reg = floors.get(playerId);
        if (reg == null || groupId == null) return;

        Integer current = reg.liveByGroupId.get(groupId);
        if (current == null) return;
        int next = Math.max(0, current - 1);
        reg.liveByGroupId.put(groupId, next);

        if (next != 0) return;

        SpawnGroup clearedGroup = reg.groupsById.get(groupId);
        if (clearedGroup == null) return;
        String nextGroupId = clearedGroup.nextGroupId();
        if (nextGroupId == null) return;

        SpawnGroup nextGroup = reg.groupsById.get(nextGroupId);
        if (nextGroup == null) {
            logger.accept("Group " + groupId + " cleared but nextGroupId '"
                    + nextGroupId + "' not registered for " + playerId);
            return;
        }

        logger.accept("Group " + groupId + " cleared — chaining to " + nextGroupId
                + " for " + playerId);
        chainSpawner.accept(playerId, nextGroup);
    }

    private void defaultChainSpawn(UUID playerId, SpawnGroup nextGroup) {
        FloorRegistration reg = floors.get(playerId);
        if (reg == null || reg.playerRef == null || reg.world == null) {
            logger.accept("Cannot chain-spawn " + nextGroup.id()
                    + ": missing player/world context");
            return;
        }
        spawnGroup(reg.playerRef, reg.world, nextGroup);
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
