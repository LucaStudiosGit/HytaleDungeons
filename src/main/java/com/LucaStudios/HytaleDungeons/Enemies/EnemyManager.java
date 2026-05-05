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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.protocol.BlockMaterial;
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
    // Keyed by partyId (== playerId for solo runs)
    private final Map<UUID, List<ScheduledFuture<?>>> pendingToSpawnByParty = new ConcurrentHashMap<>();
    private final Map<UUID, FloorRegistration> floorRegistrationMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> generationByParty = new ConcurrentHashMap<>();

    BiConsumer<UUID, SpawnGroup> chainSpawner = this::defaultChainSpawn;

    static final class FloorRegistration {
        /** Representative player ref used to obtain an entity store for NPC spawning. */
        volatile PlayerRef playerRef;
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

    /** Register the floor for a party (or solo player — partyId == playerId for solo). */
    public void registerFloor(UUID partyId, PlayerRef playerRef, World world, List<SpawnGroup> groups) {
        if (partyId == null) return;
        floorRegistrationMap.put(partyId, new FloorRegistration(playerRef, world, groups));
    }

    void registerFloorForTest(UUID partyId, List<SpawnGroup> groups) {
        if (partyId == null) return;
        floorRegistrationMap.put(partyId, new FloorRegistration(null, null, groups));
    }

    /**
     * Spawn a group of mobs for a party floor.
     * Must be called from the world thread.
     *
     * @param partyId the party (or solo player) whose floor this is
     * @param world   the world to spawn into
     * @param group   the spawn group definition
     */
    public void spawnGroup(UUID partyId, World world, SpawnGroup group) {
        if (partyId == null || world == null || group == null) return;

        FloorRegistration reg = floorRegistrationMap.get(partyId);
        if (reg == null || reg.playerRef == null || !reg.playerRef.isValid()) return;

        PlayerRef playerRef = reg.playerRef;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        Store<EntityStore> store = entityRef.getStore();

        final String groupId = group.id();
        final int generation = generationByParty.getOrDefault(partyId, 0);
        List<ScheduledFuture<?>> pending = pendingToSpawnByParty.computeIfAbsent(partyId, k -> new ArrayList<>());

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
                    int curGen = generationByParty.getOrDefault(partyId, 0);
                    if (curGen != generation) return;
                    Vector3d spawnPos = resolveAirPosition(world, position, zone, 10);
                    try {
                        Pair<Ref<EntityStore>, ?> spawned =
                                NPCPlugin.get().spawnNPC(store, arch.entityID, null, spawnPos, rotation);
                        if (spawned != null && spawned.left() != null) {
                            Ref<EntityStore> ref = spawned.left();
                            enemyStateMap.put(ref, new EnemyState(arch, fAtk, partyId, groupId));
                            incrementLive(partyId, groupId);
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
                    } catch (Throwable t) {
                        logger.accept("Failed to spawn " + arch.entityID + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }), delayMs, TimeUnit.MILLISECONDS);
                pending.add(future);
            }
        }
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
        for (UUID pid : generationByParty.keySet()) {
            generationByParty.merge(pid, 1, Integer::sum);
        }

        int cancelled = 0;
        for (List<ScheduledFuture<?>> list : pendingToSpawnByParty.values()) {
            for (ScheduledFuture<?> f : list) {
                if (f.cancel(false)) cancelled++;
            }
        }
        pendingToSpawnByParty.clear();
        enemyStateMap.clear();
        floorRegistrationMap.clear();

        staggerScheduler.schedule(() -> {
            try {
                CommandManager.get().handleCommand(ConsoleSender.INSTANCE, "entity clean --confirm");
            } catch (Throwable t) {
                logger.accept("Failed to run /entity clean: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 100L, TimeUnit.MILLISECONDS);
    }

    public void despawnAll(UUID partyId, World world) {
        if (partyId == null) return;

        generationByParty.merge(partyId, 1, Integer::sum);

        List<ScheduledFuture<?>> pending = pendingToSpawnByParty.remove(partyId);
        if (pending != null) {
            for (ScheduledFuture<?> f : pending) f.cancel(false);
        }

        List<Ref<EntityStore>> toRemove = new ArrayList<>();
        Iterator<Map.Entry<Ref<EntityStore>, EnemyState>> it = enemyStateMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Ref<EntityStore>, EnemyState> e = it.next();
            if (partyId.equals(e.getValue().partyId())) {
                toRemove.add(e.getKey());
                it.remove();
            }
        }
        floorRegistrationMap.remove(partyId);

        if (toRemove.isEmpty() || world == null) return;

        world.execute(() -> {
            for (Ref<EntityStore> ref : toRemove) {
                if (ref == null || !ref.isValid()) continue;
                try {
                    ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
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

    public void killFallenMobs(UUID partyId, int fallY) {
        if (partyId == null) return;
        List<Ref<EntityStore>> fallen = new ArrayList<>();
        for (Map.Entry<Ref<EntityStore>, EnemyState> entry : enemyStateMap.entrySet()) {
            if (!partyId.equals(entry.getValue().partyId())) continue;
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
                ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
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

        onMobDied(state.partyId(), state.groupId());

        if (runStateManager != null) {
            runStateManager.onMobKilled(state.partyId());
        }
    }

    void incrementLive(UUID partyId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(partyId);
        if (reg == null || groupId == null) return;
        reg.liveByGroupId.merge(groupId, 1, Integer::sum);
    }

    public int getLiveCount(UUID partyId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(partyId);
        if (reg == null || groupId == null) return 0;
        Integer n = reg.liveByGroupId.get(groupId);
        return n == null ? 0 : n;
    }

    void onMobDied(UUID partyId, String groupId) {
        FloorRegistration reg = floorRegistrationMap.get(partyId);
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
        if (nextGroup == null) return;
        chainSpawner.accept(partyId, nextGroup);
    }

    private void defaultChainSpawn(UUID partyId, SpawnGroup nextGroup) {
        FloorRegistration reg = floorRegistrationMap.get(partyId);
        if (reg == null || reg.world == null) return;
        spawnGroup(partyId, reg.world, nextGroup);
    }

    private Vector3d resolveAirPosition(World world, Vector3d initial, SpawnZone zone, int maxAttempts) {
        Vector3d last = initial;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Vector3d pos = attempt == 0 ? initial : new Vector3d(
                    randomBetween(zone.x0(), zone.x1()),
                    randomBetween(zone.y0(), zone.y1()),
                    randomBetween(zone.z0(), zone.z1())
            );
            BlockType bt = world.getBlockType((int) pos.x, (int) pos.y, (int) pos.z);
            if (bt == null || bt.getMaterial() != BlockMaterial.Solid) {
                return pos;
            }
            last = pos;
        }
        int x = (int) last.x;
        int z = (int) last.z;
        for (int y = (int) last.y; y <= (int) last.y + 64; y++) {
            BlockType bt = world.getBlockType(x, y, z);
            if (bt == null || bt.getMaterial() != BlockMaterial.Solid) {
                return new Vector3d(x, y, z);
            }
        }
        return last;
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
