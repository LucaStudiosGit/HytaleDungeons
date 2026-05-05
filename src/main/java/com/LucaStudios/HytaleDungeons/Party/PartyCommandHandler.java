package com.LucaStudios.HytaleDungeons.Party;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles /party chat commands for party creation and management.
 * Commands: /party create, /party join &lt;CODE&gt;, /party leave
 */
public final class PartyCommandHandler {

    private final PartyManager partyManager;
    /** Called when party state changes so the menu can re-render for affected players. */
    private final Consumer<UUID> onPartyChanged;

    public PartyCommandHandler(PartyManager partyManager, Consumer<UUID> onPartyChanged) {
        this.partyManager = partyManager;
        this.onPartyChanged = onPartyChanged;
    }

    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerChatEvent.class, this::onChat);
    }

    private void onChat(PlayerChatEvent event) {
        String content = event.getContent();
        if (content == null) return;
        String trimmed = content.trim();
        if (!trimmed.toLowerCase().startsWith("/party")) return;

        event.setCancelled(true);
        PlayerRef sender = event.getSender();
        UUID playerId = sender.getUuid();

        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length < 2) {
            sendHelp(sender);
            return;
        }

        switch (parts[1].toLowerCase()) {
            case "create" -> handleCreate(sender, playerId);
            case "join" -> {
                if (parts.length < 3) {
                    send(sender, "Usage: /party join <CODE>");
                } else {
                    handleJoin(sender, playerId, parts[2].trim().toUpperCase());
                }
            }
            case "leave" -> handleLeave(sender, playerId);
            default -> sendHelp(sender);
        }
    }

    private void handleCreate(PlayerRef sender, UUID playerId) {
        Set<UUID> oldMembers = partyManager.getPartyMembers(playerId);
        String code = partyManager.createParty(playerId, sender.getUsername());
        send(sender, "Party created! Code: " + code + " — share it with friends: /party join " + code);
        notifyAll(oldMembers);
        onPartyChanged.accept(playerId);
    }

    private void handleJoin(PlayerRef sender, UUID playerId, String code) {
        boolean wasInParty = partyManager.isInParty(playerId);
        Set<UUID> oldMembers = wasInParty ? partyManager.getPartyMembers(playerId) : Set.of();

        PartyManager.JoinResult result = partyManager.joinParty(playerId, code, sender.getUsername());
        switch (result) {
            case NOT_FOUND -> {
                send(sender, "Party code '" + code + "' not found. Check the code and try again.");
                return;
            }
            case FULL -> {
                send(sender, "Party '" + code + "' is full (" + PartyManager.MAX_PARTY_SIZE + " max).");
                return;
            }
            case SUCCESS -> {
                send(sender, "Joined party " + code + "! ("
                        + partyManager.getPartySize(playerId) + " players in party)");
                notifyAll(oldMembers);
                notifyAll(partyManager.getPartyMembers(playerId));
            }
        }
    }

    private void handleLeave(PlayerRef sender, UUID playerId) {
        if (!partyManager.isInParty(playerId)) {
            send(sender, "You are not in a party.");
            return;
        }
        Set<UUID> members = partyManager.getPartyMembers(playerId);
        partyManager.leaveParty(playerId);
        send(sender, "You left the party.");
        notifyAll(members);
    }

    private void notifyAll(Set<UUID> members) {
        for (UUID id : members) {
            onPartyChanged.accept(id);
        }
    }

    private static void sendHelp(PlayerRef player) {
        send(player, "Party commands:\n"
                + "  /party create — create a new party\n"
                + "  /party join <CODE> — join an existing party\n"
                + "  /party leave — leave your current party");
    }

    private static void send(PlayerRef player, String text) {
        player.sendMessage(Message.raw(text));
    }
}
