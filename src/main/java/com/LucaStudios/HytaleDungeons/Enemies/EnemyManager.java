package com.LucaStudios.HytaleDungeons.Enemies;

import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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

public final class EnemyManager {

    public static final double HP_VARIANCE = 0.15;
    public static final double ATK_VARIANCE = 0.10;

    public static final long MOB_SPAWN_IN_GROUP_DELAY_MS = 150L;
    private static final String MAX_HP_MODIFIER_KEY = "hytaleDungeons:rolled_max";

    private final RunStateManager runStateManager;
    private final Consumer<String> logger;
    private final Random random = new Random();
    private final ScheduledExecutorService staggerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "enemy-spawn-stagger");
        t.setDaemon(true);
        return t;
    });

    private final Map<Ref<EntityStore>, EnemyState> enemyStateMap = new ConcurrentHashMap<>();
    private final Map<UUID, List<ScheduledFuture<?>>> pendingToSpawnByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, FloorRegistration> floorRegistrationMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> generationByPlayer = new ConcurrentHashMap<>();

    BiConsumer<UUID, SpawnGroup> chainSpawner = this::defaultChainSpawn;

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

    public void registerFloor(UUID playerId, PlayerRef playerRef, World world, List<SpawnGroup> groups) {
        if (playerId == null) return;
        floorRegistrationMap.put(playerId, new FloorRegistration(playerRef, world, groups));
    }

    void registerFloorForTest(UUID playerId, List<SpawnGroup> groups) {
        if (playerId == null) return;
        floorRegistrationMap.put(playerId, new FloorRegistration(null, null, groups));
    }

    /**
     * Must be called from the WorldThread.
     */
    public void spawnGroup(PlayerRef playerRef, World world, SpawnGroup group) {
        if (playerRef == null || world == null || group == null) return;
        if (!playerRef.isValid()) return;

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        final UUID playerId = playerRef.getUuid();
        final String groupId = group.id();
        final int generation = generationByPlayer.getOrDefault(playerId, 0);
        List<ScheduledFuture<?>> pending = pendingToSpawnByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>());
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
                final long delayMs = (long) index * MOB_SPAWN_IN_GROUP_DELAY_MS;
                index++;

                ScheduledFuture<?> future = staggerScheduler.schedule(() -> world.execute(() -> {
                    if (!playerRef.isValid() || !entityRef.isValid()) return;
                    int curGen = generationByPlayer.getOrDefault(playerId, 0);
                    if (curGen != generation) return;
                    try {
                        Pair<Ref<EntityStore>, ?> spawned =
                                NPCPlugin.get().spawnNPC(store, arch.entityID, null, position, rotation);
                        if (spawned != null && spawned.left() != null) {
                            Ref<EntityStore> ref = spawned.left();
                            enemyStateMap.put(ref, new EnemyState(arch, fAtk, playerId, groupId));
                            incrementLive(playerId, groupId);
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
                        // TODO: play "pop"/"swish" SFX at position once Hytale audio API is identified
                    } catch (Throwable t) {
                        logger.accept("Failed to spawn " + arch.entityID + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }), delayMs, TimeUnit.MILLISECONDS);
                pending.add(future);
            }
        }
        // TODO: Track live mobs and chain nextGroup on clear (Enemy AI GDD)
    }

    public int removeTrackedMobs() {
        int removed = 0;
        for (Map.Entry<Ref<EntityStore>, EnemyState> e : enemyStateMap.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            if (ref == null || !ref.isValid()) continue;
            try {
                Store<EntityStore> s = ref.getStore();
                s.removeEntity(ref, RemoveReason.REMOVE);
                removed++;
            } catch (Throwable t) {
                // Entity already dead/removed by the native pipeline — safe to ignore.
            }
        }
        return removed;
    }

    public void cleanAllEntities() {
        for (UUID pid : generationByPlayer.keySet()) {
            generationByPlayer.merge(pid, 1, Integer::sum);
        }

        int cancelled = 0;
        for (List<ScheduledFuture<?>> list : pendingToSpawnByPlayer.values()) {
            for (ScheduledFuture<?> f : list) {
                if (f.cancel(false)) cancelled++;
            }
        }
        pendingToSpawnByPlayer.clear();
        enemyStateMap.clear();
        floorRegistrationMap.clear();

        final int cancelledFinal = cancelled;
        staggerScheduler.schedule(() -> {
            try {
                CommandManager.get().handleCommand(ConsoleSender.INSTANCE, "entity clean --confirm");
            } catch (Throwable t) {
                logger.accept("Failed to run /entity clean: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 100L, TimeUnit.MILLISECONDS);
    }

    public void despawnAll(UUID playerId, World world) {
        if (playerId == null) return;

        generationByPlayer.merge(playerId, 1, Integer::sum);

        List<ScheduledFuture<?>> pending = pendingToSpawnByPlayer.remove(playerId);
        int cancelled = 0;
        if (pending != null) {
            for (ScheduledFuture<?> f : pending) {
                if (f.cancel(false)) cancelled++;
            }
        }

        List<Ref<EntityStore>> toRemove = new ArrayList<>();
        Iterator<Map.Entry<Ref<EntityStore>, EnemyState>> it = enemyStateMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, EnemyState> e = it.next();
            if (playerId.equals(e.getValue().playerId())) {
                toRemove.add(e.getKey());
                it.remove();
            }
        }
        floorRegistrationMap.remove(playerId);

        if (toRemove.isEmpty() && cancelled == 0) return;

        if (world == null || toRemove.isEmpty()) {
            return;
        }

        final int cancelledFinal = cancelled;
        world.execute(() -> {
            int removed = 0;
            for (Ref<EntityStore> ref : toRemove) {
                if (ref == null || !ref.isValid()) continue;
                try {
                    Store<EntityStore> s = ref.getStore();
                    s.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } catch (Throwable t) {
                    logger.accept("Failed to remove mob: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        });
    }

    public EnemyState getState(Ref<EntityStore> ref) {
        return (ref == null) ? null : enemyStateMap.get(ref);
    }

    public void killFallenMobs(UUID playerId, int fallY) {
        if (playerId == null) return;
        List<Ref<EntityStore>> fallen = new ArrayList<>();
        for (Map.Entry<Ref<EntityStore>, EnemyState> entry : enemyStateMap.entrySet()) {
            if (!playerId.equals(entry.getValue().playerId())) continue;
            Ref<EntityStore> ref = entry.getKey();
            if (ref == null || !ref.isValid()) continue;
            try {
                Store<EntityStore> store = ref.getStore();
                TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
                if (tc == null) continue;
                double y = tc.getTransform().getPosition().y;
                if (y < fallY) fallen.add(ref);
            } catch (Throwable t) {
                // Bad ECS read during archetype transition — skip this tick.
            }
        }
        if (fallen.isEmpty()) return;

        for (Ref<EntityStore> ref : fallen) {
            try {
                Store<EntityStore> s = ref.getStore();
                s.removeEntity(ref, RemoveReason.REMOVE);
            } catch (Throwable t) {
                // Already removed by native pipeline — safe to ignore.
            }
            onNativeDeath(ref);
        }
    }

    public void onNativeDeath(Ref<EntityStore> ref) {
        if (ref == null) return;
        EnemyState state = enemyStateMap.remove(ref);
        if (state == null) return;

        onMobDied(state.playerId(), state.groupId());

        if (runStateManager != null) {
            runStateManager.onMobKilled(state.playerId());
        }
    }

    void incrementLive(UUID playerId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(playerId);
        if (reg == null || groupId == null) return;
        reg.liveByGroupId.merge(groupId, 1, Integer::sum);
    }

    public int getLiveCount(UUID playerId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(playerId);
        if (reg == null || groupId == null) return 0;
        Integer n = reg.liveByGroupId.get(groupId);
        return n == null ? 0 : n;
    }

    void onMobDied(UUID playerId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(playerId);
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
            return;
        }
        chainSpawner.accept(playerId, nextGroup);
    }

    private void defaultChainSpawn(UUID playerId, SpawnGroup nextGroup) {
        FloorRegistration reg = floorRegistrationMap.get(playerId);
        if (reg == null || reg.playerRef == null || reg.world == null) {
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
        double multiply = 1.0 + (random.nextDouble() * 2.0 - 1.0) * variance;
        return Math.max(1, (int) Math.round(base * multiply));
    }
}
