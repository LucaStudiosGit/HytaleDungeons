package com.LucaStudios.HytaleDungeons.Restrictions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ForkedChainId;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.CancelInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class NoHotbarSwitchRestriction {

    private static final byte LOCKED_SLOT = 0; // visible slot 1

    private final JavaPlugin plugin;

    public NoHotbarSwitchRestriction(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        PacketAdapters.registerInbound(new HotbarLockFilter());
    }

    private static final class HotbarLockFilter implements PlayerPacketFilter {

        @Override
        public boolean test(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
            if (!(packet instanceof SyncInteractionChains syncPacket)) {
                return false;
            }

            List<SyncInteractionChain> keep = new ArrayList<>();
            SyncInteractionChain blockedChain = null;

            for (SyncInteractionChain chain : syncPacket.updates) {
                boolean isHotbarSwap =
                        chain.interactionType == InteractionType.SwapFrom &&
                                chain.data != null &&
                                chain.initial &&
                                chain.data.targetSlot != LOCKED_SLOT;

                if (isHotbarSwap && blockedChain == null) {
                    blockedChain = chain;
                } else {
                    keep.add(chain);
                }
            }

            if (blockedChain == null) {
                return false;
            }

            handleBlockedHotbarSwitch(
                    playerRef,
                    blockedChain.activeHotbarSlot,
                    blockedChain.chainId,
                    blockedChain.forkedId
            );

            if (!keep.isEmpty()) {
                syncPacket.updates = keep.toArray(new SyncInteractionChain[0]);
                return false;
            }

            return true;
        }

        private void handleBlockedHotbarSwitch(
                PlayerRef playerRef,
                int originalSlot,
                int chainId,
                ForkedChainId forkedId
        ) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> store = entityRef.getStore();
            World world = store.getExternalData().getWorld();

            world.execute(() -> {
                Player player = store.getComponent(entityRef, Player.getComponentType());
                if (player == null) {
                    return;
                }

                playerRef.getPacketHandler().writeNoCache(
                        new CancelInteractionChain(chainId, forkedId)
                );

                InventoryComponent.Hotbar hotbar = store.getComponent(entityRef, InventoryComponent.Hotbar.getComponentType());
                if (hotbar != null) hotbar.setActiveSlot((byte) 0);

                playerRef.getPacketHandler().writeNoCache(
                        new SetActiveSlot(InventoryComponent.HOTBAR_SECTION_ID, 0)
                );
            });
        }
    }
}