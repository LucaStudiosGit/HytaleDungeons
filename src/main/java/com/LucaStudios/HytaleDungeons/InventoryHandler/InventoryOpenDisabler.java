package com.LucaStudios.HytaleDungeons.InventoryHandler;

import com.LucaStudios.HytaleDungeons.Pages.InventoryPage;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.packets.window.ClientOpenWindow;
import com.hypixel.hytale.protocol.packets.window.CloseWindow;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class InventoryOpenDisabler {

    private final JavaPlugin plugin;
    private final InventoryPage inventoryDisabledPageOpener;
    private final Set<UUID> hookedInventoryOpenPackets = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, AtomicInteger> pendingForcedInventoryCloses = new ConcurrentHashMap<>();

    public InventoryOpenDisabler(JavaPlugin plugin, InventoryPage inventoryDisabledPageOpener) {
        this.plugin = plugin;
        this.inventoryDisabledPageOpener = inventoryDisabledPageOpener;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        var entityRef = event.getPlayerRef();
        var store = entityRef.getStore();
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!entityRef.isValid()) {
                return;
            }

            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                plugin.getLogger().at(Level.WARNING)
                        .log("Skipping inventory packet hook because PlayerRef component is missing.");
                return;
            }

            hookInventoryOpenPacket(playerRef);
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerUuid = event.getPlayerRef().getUuid();
        hookedInventoryOpenPackets.remove(playerUuid);
        pendingForcedInventoryCloses.remove(playerUuid);
    }

    private void hookInventoryOpenPacket(PlayerRef playerRef) {
        PacketHandler packetHandler = playerRef.getPacketHandler();
        if (packetHandler == null) {
            plugin.getLogger().at(Level.WARNING)
                    .log("Skipping inventory packet hook because packet handler is unavailable.");
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        String username = playerRef.getUsername();
        if (!hookedInventoryOpenPackets.add(playerUuid)) {
            return;
        }
        pendingForcedInventoryCloses.putIfAbsent(playerUuid, new AtomicInteger(0));

        if (!(packetHandler instanceof GenericPacketHandler genericPacketHandler)) {
            hookedInventoryOpenPackets.remove(playerUuid);
            plugin.getLogger().at(Level.WARNING).log(
                    "Skipping inventory packet hook for %s because packet handler type %s is unsupported.",
                    username,
                    packetHandler.getClass().getName()
            );
            return;
        }

        try {
            var handlersField = GenericPacketHandler.class.getDeclaredField("handlers");
            handlersField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Consumer<ToServerPacket>[] handlers = (Consumer<ToServerPacket>[]) handlersField.get(genericPacketHandler);
            int openPacketId = ClientOpenWindow.PACKET_ID;
            int closePacketId = CloseWindow.PACKET_ID;
            Consumer<ToServerPacket> originalOpenHandler = handlers[openPacketId];
            Consumer<ToServerPacket> originalCloseHandler = handlers[closePacketId];

            genericPacketHandler.registerHandler(openPacketId, packet -> {
                if (!(packet instanceof ClientOpenWindow openWindowPacket)) {
                    if (originalOpenHandler != null) {
                        originalOpenHandler.accept(packet);
                    }
                    return;
                }

                if (openWindowPacket.type == WindowType.PocketCrafting) {
                    pendingForcedInventoryCloses
                            .computeIfAbsent(playerUuid, ignored -> new AtomicInteger(0))
                            .incrementAndGet();
                    inventoryDisabledPageOpener.open(playerRef);
                    plugin.getLogger().at(Level.INFO)
                            .log("Player %s opened inventory. Redirected to custom page.", username);
                    return;
                }

                if (originalOpenHandler != null) {
                    originalOpenHandler.accept(packet);
                }
            });

            genericPacketHandler.registerHandler(closePacketId, packet -> {
                if (packet instanceof CloseWindow) {
                    AtomicInteger pending = pendingForcedInventoryCloses.get(playerUuid);
                    if (pending != null && pending.getAndUpdate(current -> Math.max(0, current - 1)) > 0) {
                        return;
                    }
                }

                if (originalCloseHandler != null) {
                    originalCloseHandler.accept(packet);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            hookedInventoryOpenPackets.remove(playerUuid);
            pendingForcedInventoryCloses.remove(playerUuid);
            plugin.getLogger().at(Level.SEVERE)
                    .withCause(exception)
                    .log("Failed to hook inventory open packet for player %s", username);
        }
    }
}
