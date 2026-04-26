package com.LucaStudios.HytaleDungeons.Health;

import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class HealthManager {

    public static final float POTION_HEAL_RATIO = 0.80f;
    public static final long POTION_COOLDOWN_MS = 30_000L;

    private final RunStateManager runStateManager;
    private final ConcurrentHashMap<UUID, PlayerHpState> states = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public HealthManager(RunStateManager runStateManager, Consumer<String> logger) {
        this.runStateManager = runStateManager;
        this.logger = logger;
    }

    public void initPlayer(UUID playerId, PlayerRef playerRef,
                           Ref<EntityStore> entityRef, Store<EntityStore> store) {
        states.put(playerId, new PlayerHpState(playerRef));
        maximizeHp(entityRef, store);
    }

    public void removePlayer(UUID playerId) {
        states.remove(playerId);
    }

    public void resetHealth(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return;
        PlayerRef playerRef = state.playerRef;
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        state.potionCooldownEndMs = 0L;
        maximizeHp(entityRef, entityRef.getStore());
    }

    public int usePotion(UUID playerId) {
        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            return -1;
        }
        PlayerHpState state = states.get(playerId);
        if (state == null) return -1;
        if (System.currentTimeMillis() < state.potionCooldownEndMs) return -1;

        PlayerRef playerRef = state.playerRef;
        if (playerRef == null || !playerRef.isValid()) return -1;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return -1;
        Store<EntityStore> store = entityRef.getStore();

        EntityStatMap statMap = store.getComponent(entityRef, EntityStatMap.getComponentType());
        if (statMap == null) return -1;
        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null) return -1;

        float before = hp.get();
        float max = hp.getMax();
        if (before <= 0f) return -1; // dead players can't chug
        float healAmount = max * POTION_HEAL_RATIO;
        float after = Math.min(max, before + healAmount);
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), after);
        state.potionCooldownEndMs = System.currentTimeMillis() + POTION_COOLDOWN_MS;

        int healed = Math.round(after - before);
        return healed;
    }

    public boolean applyEnvironmentalDamage(UUID playerId, float fraction) {
        EntityStatValue hp = readHp(playerId);
        if (hp == null) return false;
        float curr = hp.get();
        if (curr <= 0f) return false;
        float max = hp.getMax();
        float damage = max * fraction;
        float next = Math.max(0f, curr - damage);
        PlayerHpState state = states.get(playerId);
        if (state == null) return false;
        Ref<EntityStore> entityRef = state.playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return false;
        EntityStatMap statMap = entityRef.getStore().getComponent(entityRef, EntityStatMap.getComponentType());
        if (statMap == null) return false;
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), next);
        return next <= 0f;
    }

    public boolean isPotionOnCooldown(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return false;
        return System.currentTimeMillis() < state.potionCooldownEndMs;
    }

    public long getPotionCooldownRemainingMs(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return 0L;
        return Math.max(0L, state.potionCooldownEndMs - System.currentTimeMillis());
    }

    public float getCurrentHp(UUID playerId) {
        EntityStatValue hp = readHp(playerId);
        return hp == null ? -1f : hp.get();
    }

    public float getMaxHp(UUID playerId) {
        EntityStatValue hp = readHp(playerId);
        return hp == null ? -1f : hp.getMax();
    }

    private EntityStatValue readHp(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return null;
        PlayerRef playerRef = state.playerRef;
        if (playerRef == null || !playerRef.isValid()) return null;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return null;
        try {
            EntityStatMap statMap = entityRef.getStore().getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMap == null) return null;
            return statMap.get(DefaultEntityStatTypes.getHealth());
        } catch (Throwable t) {
            return null;
        }
    }

    private float maximizeHp(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        if (entityRef == null || !entityRef.isValid() || store == null) return 0f;
        try {
            EntityStatMap statMap = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMap == null) return 0f;
            int healthIdx = DefaultEntityStatTypes.getHealth();
            EntityStatValue hp = statMap.get(healthIdx);
            if (hp == null) return 0f;
            float nativeMax = hp.getMax();
            statMap.maximizeStatValue(healthIdx);
            return nativeMax;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private void log(String format, Object... args) {
        logger.accept(String.format(format, args));
    }

    private static final class PlayerHpState {
        final PlayerRef playerRef;
        long potionCooldownEndMs;

        PlayerHpState(PlayerRef playerRef) {
            this.playerRef = playerRef;
            this.potionCooldownEndMs = 0L;
        }
    }
}
