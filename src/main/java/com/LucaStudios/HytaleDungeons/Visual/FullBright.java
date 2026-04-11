package com.LucaStudios.HytaleDungeons.Visual;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Attaches a max-brightness DynamicLight to the player entity so they can
 * see everything clearly regardless of ambient light level.
 *
 * Uses the DynamicLight ECS component, which is tracked by
 * EntitySystems$DynamicLightTracker and pushed to clients via
 * DynamicLightUpdate packets every tick — guaranteed to arrive after chunks load.
 *
 * Must be called on the world thread.
 */
public final class FullBright {

    // Max-brightness white light at maximum radius.
    // byte -1 = 0xFF unsigned = 255, the highest value the field can hold.
    private static final ColorLight FULLBRIGHT_LIGHT =
            new ColorLight((byte) -1, (byte) -1, (byte) -1, (byte) -1);

    private FullBright() {}

    /**
     * Applies fullbright to the given player. Safe to call multiple times —
     * replaces any existing DynamicLight component.
     */
    public static void apply(PlayerRef playerRef) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;

        Store<EntityStore> store = entityRef.getStore();
        store.addComponent(entityRef, DynamicLight.getComponentType(), new DynamicLight(FULLBRIGHT_LIGHT));
    }
}
