package com.LucaStudios.HytaleDungeons.Enemies;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.function.Consumer;

/**
 * Observes the native {@link DeathComponent} being added to an entity and,
 * if that entity is one of ours, routes it to {@link EnemyManager#onNativeDeath}
 * for live-count bookkeeping and chain-spawn dispatch.
 *
 * <p>The native pipeline ({@code DamageSystems$ApplyDamage}) is still responsible
 * for subtracting HP, adding the death component, playing the death animation,
 * and removing the corpse — we just piggyback on it.</p>
 */
public final class MobDeathObserver extends DeathSystems.OnDeathSystem {

    private final EnemyManager enemyManager;
    private final Consumer<String> logger;

    public MobDeathObserver(EnemyManager enemyManager, Consumer<String> logger) {
        this.enemyManager = enemyManager;
        this.logger = logger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref,
                                 DeathComponent component,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) return;
        EnemyState state = enemyManager.getState(ref);
        if (state == null) return;
        try {
            enemyManager.onNativeDeath(ref);
        } catch (Throwable t) {
            logger.accept("MobDeathObserver failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
