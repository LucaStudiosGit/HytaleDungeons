package com.LucaStudios.HytaleDungeons.Party;

import com.LucaStudios.HytaleDungeons.Run.RunState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared run state for a co-op party. Each player still has an individual RunData for per-player stats. */
public final class PartyRunData {

    private final UUID partyId;
    private final Set<UUID> members;

    private volatile int sharedRevivesRemaining;
    private final AtomicInteger sharedMobsRemaining = new AtomicInteger(0);
    private final AtomicInteger totalMobsKilled = new AtomicInteger(0);
    private volatile int currentFloor;
    private volatile RunState state;
    private volatile long runStartMs;

    private final Map<UUID, Boolean> isPlayerDowned = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> downedTimers = new ConcurrentHashMap<>();
    private final Set<UUID> offersPicked = ConcurrentHashMap.newKeySet();

    public PartyRunData(UUID partyId, Set<UUID> members, int startingRevives, int startingFloor) {
        this.partyId = partyId;
        this.members = members;
        this.sharedRevivesRemaining = startingRevives;
        this.currentFloor = startingFloor;
        this.state = RunState.FLOOR_ACTIVE;
        this.runStartMs = System.currentTimeMillis();
    }

    public UUID getPartyId() { return partyId; }
    public Set<UUID> getMembers() { return members; }

    public int getSharedRevivesRemaining() { return sharedRevivesRemaining; }
    public void decrementRevives() { if (sharedRevivesRemaining > 0) sharedRevivesRemaining--; }
    public void resetRevives(int max) { sharedRevivesRemaining = max; }

    public int getSharedMobsRemaining() { return sharedMobsRemaining.get(); }
    public void setSharedMobsRemaining(int count) { sharedMobsRemaining.set(count); }
    public int decrementAndGetMobs() { return sharedMobsRemaining.decrementAndGet(); }

    public int getTotalMobsKilled() { return totalMobsKilled.get(); }
    public void incrementMobsKilled() { totalMobsKilled.incrementAndGet(); }

    public int getCurrentFloor() { return currentFloor; }
    public void setCurrentFloor(int floor) { currentFloor = floor; }

    public RunState getState() { return state; }
    public void setState(RunState state) { this.state = state; }

    public long getRunDurationMs() { return System.currentTimeMillis() - runStartMs; }

    public boolean isPlayerDowned(UUID playerId) {
        return Boolean.TRUE.equals(isPlayerDowned.get(playerId));
    }
    public void setPlayerDowned(UUID playerId, boolean downed) {
        if (downed) isPlayerDowned.put(playerId, true);
        else isPlayerDowned.remove(playerId);
    }
    public long downedCount() {
        return isPlayerDowned.values().stream().filter(Boolean.TRUE::equals).count();
    }

    /** Cancels and removes any existing downed timer for this player. */
    public void cancelDownedTimer(UUID playerId) {
        ScheduledFuture<?> old = downedTimers.remove(playerId);
        if (old != null) old.cancel(false);
    }

    /** Sets the downed timer for a player, cancelling any prior one. */
    public void putDownedTimer(UUID playerId, ScheduledFuture<?> timer) {
        ScheduledFuture<?> old = downedTimers.put(playerId, timer);
        if (old != null) old.cancel(false);
    }

    /** Cancels all downed timers for every party member. */
    public void cancelAllDownedTimers() {
        for (ScheduledFuture<?> f : downedTimers.values()) f.cancel(false);
        downedTimers.clear();
    }

    /** Records that this player picked their between-floors offer.
     *  Returns true if all party members have now picked. */
    public boolean markOfferPicked(UUID playerId) {
        offersPicked.add(playerId);
        return offersPicked.containsAll(members);
    }
    public void clearOfferPicks() { offersPicked.clear(); }

    public void reset(int maxRevives, int startFloor) {
        sharedRevivesRemaining = maxRevives;
        currentFloor = startFloor;
        state = RunState.FLOOR_ACTIVE;
        sharedMobsRemaining.set(0);
        totalMobsKilled.set(0);
        runStartMs = System.currentTimeMillis();
        isPlayerDowned.clear();
        cancelAllDownedTimers();
        offersPicked.clear();
    }
}
