package com.LucaStudios.HytaleDungeons.Health;

import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.function.Consumer;

/**
 * Observes the native {@link DeathComponent} being added to a player entity
 * and hands off to {@link RunStateManager#onPlayerDeath} so our respawn timer
 * / lives bookkeeping fire at the same moment as the native death animation.
 *
 * <p>The native damage pipeline ({@code DamageSystems$ApplyDamage}) is still
 * responsible for subtracting HP, adding the death component, and playing the
 * death animation — we just piggyback on the state change.</p>
 */
public final class PlayerDeathObserver extends DeathSystems.OnDeathSystem {

    private final RunStateManager runStateManager;
    private final Consumer<String> logger;

    public PlayerDeathObserver(RunStateManager runStateManager, Consumer<String> logger) {
        this.runStateManager = runStateManager;
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
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        // Suppress Hytale's native death menu — our RunStateManager drives
        // the respawn flow and (eventually) our own death UI.
        component.setShowDeathMenu(false);

        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) return;

        try {
            runStateManager.onPlayerDeath(playerRef.getUuid(), playerRef);
        } catch (Throwable t) {
            logger.accept("PlayerDeathObserver failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
