package com.LucaStudios.HytaleDungeons.Health;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class PotionKeyHandler implements PlayerPacketWatcher {

    private static final byte POTION_SLOT = 3; // key "4" = 0-based index 3

    private final HealthManager healthManager;

    public PotionKeyHandler(HealthManager healthManager) {
        this.healthManager = healthManager;
    }

    public void register() {
        PacketAdapters.registerInbound(this);
    }

    @Override
    public void accept(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (!(packet instanceof SyncInteractionChains syncPacket)) return;

        for (SyncInteractionChain chain : syncPacket.updates) {
            if (chain.interactionType == InteractionType.SwapFrom
                    && chain.data != null
                    && chain.initial
                    && chain.data.targetSlot == POTION_SLOT) {
                triggerPotion(playerRef);
                return;
            }
        }
    }

    private void triggerPotion(PlayerRef playerRef) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!entityRef.isValid()) return;
            healthManager.usePotion(playerRef.getUuid());
        });
    }
}
