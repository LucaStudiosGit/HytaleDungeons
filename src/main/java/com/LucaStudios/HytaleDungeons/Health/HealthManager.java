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

/**
 * Thin wrapper over the player's native {@link EntityStatMap} HEALTH stat.
 *
 * <p>Damage application is entirely handled by the native damage pipeline —
 * {@link com.LucaStudios.HytaleDungeons.Combat.DamageInterceptor} rewrites the
 * incoming amount and lets {@code DamageSystems$ApplyDamage} subtract from the
 * stat. This class exists only to (a) refill the player's HP on respawn via
 * the native max value, (b) own potion cooldown state, and (c) expose a
 * {@link #usePotion} helper that reads/writes the native stat directly.</p>
 */
public final class HealthManager {

    // --- Tuning Knobs (from GDD) ---
    public static final int POTION_HEAL_AMOUNT = 50;
    public static final long POTION_COOLDOWN_MS = 8000L;

    private final RunStateManager runStateManager;
    private final ConcurrentHashMap<UUID, PlayerHpState> states = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public HealthManager(RunStateManager runStateManager, Consumer<String> logger) {
        this.runStateManager = runStateManager;
        this.logger = logger;
    }

    /**
     * Initialize HP tracking for a player. We do NOT apply a max-HP modifier —
     * calling {@code EntityStatMap.putModifier} triggers
     * {@code EntityStatValue.computeModifiers(asset)} which resets max from
     * the asset baseline (100) and wipes the pre-set native max (200). So we
     * leave the native max alone and just fill current HP up to it.
     * Must be called from the world thread.
     */
    public void initPlayer(UUID playerId, PlayerRef playerRef,
                           Ref<EntityStore> entityRef, Store<EntityStore> store) {
        states.put(playerId, new PlayerHpState(playerRef));
        float nativeMax = maximizeHp(entityRef, store);
        log("Player %s HP initialized to %.0f/%.0f (native max)", playerId, nativeMax, nativeMax);
    }

    /** Remove HP tracking for a player. Called on disconnect. */
    public void removePlayer(UUID playerId) {
        states.remove(playerId);
    }

    /**
     * Refill the player's native HP to its native max. Called on respawn,
     * new floor, and new run. Must be called from the world thread.
     */
    public void resetHealth(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return;
        PlayerRef playerRef = state.playerRef;
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        state.potionCooldownEndMs = 0L;
        float nativeMax = maximizeHp(entityRef, entityRef.getStore());
        log("Player %s HP reset to %.0f", playerId, nativeMax);
    }

    /**
     * Use a health potion. Reads the player's current native HP, adds
     * {@link #POTION_HEAL_AMOUNT} (clamped to max), writes it back, and starts
     * the cooldown. Must be called from the world thread.
     *
     * @return HP actually healed, or -1 if potion use was blocked (wrong state
     *         or on cooldown).
     */
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
        float after = Math.min(max, before + POTION_HEAL_AMOUNT);
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), after);
        state.potionCooldownEndMs = System.currentTimeMillis() + POTION_COOLDOWN_MS;

        int healed = Math.round(after - before);
        log("Player %s used potion: healed %d HP, now %.0f/%.0f", playerId, healed, after, max);
        return healed;
    }

    /** True if the potion is currently on cooldown for this player. */
    public boolean isPotionOnCooldown(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return false;
        return System.currentTimeMillis() < state.potionCooldownEndMs;
    }

    /** Remaining potion cooldown in milliseconds (0 if ready). */
    public long getPotionCooldownRemainingMs(UUID playerId) {
        PlayerHpState state = states.get(playerId);
        if (state == null) return 0L;
        return Math.max(0L, state.potionCooldownEndMs - System.currentTimeMillis());
    }

    /**
     * Read current HP from the native HEALTH stat. Returns -1 if the player
     * is unknown, invalid, or the stat is missing. Must be called from the
     * world thread.
     */
    public float getCurrentHp(UUID playerId) {
        EntityStatValue hp = readHp(playerId);
        return hp == null ? -1f : hp.get();
    }

    /**
     * Read max HP from the native HEALTH stat. Returns -1 if the player is
     * unknown, invalid, or the stat is missing. Must be called from the
     * world thread.
     */
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
        EntityStatMap statMap = entityRef.getStore().getComponent(entityRef, EntityStatMap.getComponentType());
        if (statMap == null) return null;
        return statMap.get(DefaultEntityStatTypes.getHealth());
    }

    // ── Native stat helpers ──────────────────────────────────────────────

    /**
     * Fill the player's HEALTH stat to its current native max without touching
     * modifiers (avoids triggering {@code computeModifiers} which would reset
     * max from the asset baseline). Returns the native max value.
     */
    private float maximizeHp(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        if (entityRef == null || !entityRef.isValid() || store == null) return 0f;
        EntityStatMap statMap = store.getComponent(entityRef, EntityStatMap.getComponentType());
        if (statMap == null) return 0f;
        int healthIdx = DefaultEntityStatTypes.getHealth();
        EntityStatValue hp = statMap.get(healthIdx);
        if (hp == null) return 0f;
        float nativeMax = hp.getMax();
        statMap.maximizeStatValue(healthIdx);
        return nativeMax;
    }

    private void log(String format, Object... args) {
        logger.accept(String.format(format, args));
    }

    /** Per-player HP-adjacent state — native stat owns current/max HP. */
    private static final class PlayerHpState {
        final PlayerRef playerRef;
        long potionCooldownEndMs;

        PlayerHpState(PlayerRef playerRef) {
            this.playerRef = playerRef;
            this.potionCooldownEndMs = 0L;
        }
    }
}
