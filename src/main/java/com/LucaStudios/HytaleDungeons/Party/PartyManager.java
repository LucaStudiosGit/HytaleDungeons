package com.LucaStudios.HytaleDungeons.Party;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PartyManager {

    public static final int MAX_PARTY_SIZE = 4;

    public enum JoinResult { SUCCESS, NOT_FOUND, FULL }

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    private final Map<String, UUID> codeToParty   = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> partyMembers = new ConcurrentHashMap<>();
    private final Map<UUID, String>  partyCodes    = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>    playerToParty = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>    partyLeaders  = new ConcurrentHashMap<>();
    private final Map<UUID, String>  usernames     = new ConcurrentHashMap<>();

    public String createParty(UUID leaderId, String username) {
        leaveParty(leaderId);
        if (username != null) usernames.put(leaderId, username);

        String code   = generateCode();
        UUID   partyId = UUID.randomUUID();

        Set<UUID> members = ConcurrentHashMap.newKeySet();
        members.add(leaderId);

        codeToParty.put(code, partyId);
        partyMembers.put(partyId, members);
        partyCodes.put(partyId, code);
        playerToParty.put(leaderId, partyId);
        partyLeaders.put(partyId, leaderId);

        return code;
    }

    public JoinResult joinParty(UUID playerId, String code, String username) {
        UUID partyId = codeToParty.get(code.toUpperCase());
        if (partyId == null) return JoinResult.NOT_FOUND;

        Set<UUID> members = partyMembers.get(partyId);
        if (members == null) return JoinResult.NOT_FOUND;

        // Cap check before mutating state — but only if this player isn't already in the party.
        if (!members.contains(playerId) && members.size() >= MAX_PARTY_SIZE) {
            return JoinResult.FULL;
        }

        leaveParty(playerId);
        if (username != null) usernames.put(playerId, username);

        members.add(playerId);
        playerToParty.put(playerId, partyId);
        return JoinResult.SUCCESS;
    }

    public void leaveParty(UUID playerId) {
        UUID partyId = playerToParty.remove(playerId);
        if (partyId == null) return;

        Set<UUID> members = partyMembers.get(partyId);
        if (members != null) {
            members.remove(playerId);
            if (members.isEmpty()) {
                disbandParty(partyId);
            } else if (playerId.equals(partyLeaders.get(partyId))) {
                partyLeaders.put(partyId, members.iterator().next());
            }
        }
    }

    public void disbandParty(UUID partyId) {
        String code = partyCodes.remove(partyId);
        if (code != null) codeToParty.remove(code);
        Set<UUID> members = partyMembers.remove(partyId);
        if (members != null) {
            for (UUID m : members) playerToParty.remove(m);
        }
        partyLeaders.remove(partyId);
    }

    public void removePlayer(UUID playerId) {
        leaveParty(playerId);
        usernames.remove(playerId);
    }

    public String getUsername(UUID playerId) {
        return usernames.get(playerId);
    }

    public UUID getLeaderId(UUID playerId) {
        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return null;
        return partyLeaders.get(partyId);
    }

    /** Returns the party UUID for this player, or the player's own UUID if solo. */
    public UUID getPartyId(UUID playerId) {
        return playerToParty.getOrDefault(playerId, playerId);
    }

    /** Returns all party member UUIDs, or a singleton set if solo. */
    public Set<UUID> getPartyMembers(UUID playerId) {
        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return Set.of(playerId);
        Set<UUID> members = partyMembers.get(partyId);
        return members != null ? Collections.unmodifiableSet(members) : Set.of(playerId);
    }

    public Optional<String> getPartyCode(UUID playerId) {
        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return Optional.empty();
        return Optional.ofNullable(partyCodes.get(partyId));
    }

    public boolean isInParty(UUID playerId) {
        return playerToParty.containsKey(playerId);
    }

    public boolean isLeader(UUID playerId) {
        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return false;
        return playerId.equals(partyLeaders.get(partyId));
    }

    public int getPartySize(UUID playerId) {
        return getPartyMembers(playerId).size();
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        String candidate;
        do {
            sb.setLength(0);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            candidate = sb.toString();
        } while (codeToParty.containsKey(candidate));
        return candidate;
    }
}
