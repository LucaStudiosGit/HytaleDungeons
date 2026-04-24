package com.LucaStudios.HytaleDungeons.Enemies;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

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
    public void onComponentAdded(@NotNull Ref<EntityStore> ref,
                                 @NotNull DeathComponent component,
                                 @NotNull Store<EntityStore> store,
                                 @NotNull CommandBuffer<EntityStore> commandBuffer) {
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
