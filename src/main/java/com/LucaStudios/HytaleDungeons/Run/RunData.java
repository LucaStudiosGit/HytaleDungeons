package com.LucaStudios.HytaleDungeons.Run;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;

/**
 * Mutable per-player run state. Owned exclusively by {@link RunStateManager}.
 */
public final class RunData {

    private final UUID playerId;
    private RunState state;
    private int currentFloor;
    private int livesRemaining;
    private int mobsRemaining;
    private PlayerRef playerRef;

    public RunData(UUID playerId, int maxLives, int startingFloor) {
        this.playerId = playerId;
        this.state = RunState.FLOOR_ACTIVE;
        this.currentFloor = startingFloor;
        this.livesRemaining = maxLives;
        this.mobsRemaining = 0;
    }

    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    void setPlayerRef(PlayerRef playerRef) {
        this.playerRef = playerRef;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public RunState getState() {
        return state;
    }

    void setState(RunState state) {
        this.state = state;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    void setCurrentFloor(int floor) {
        this.currentFloor = floor;
    }

    public int getLivesRemaining() {
        return livesRemaining;
    }

    void decrementLives() {
        if (livesRemaining > 0) {
            livesRemaining--;
        }
    }

    void resetLives(int maxLives) {
        this.livesRemaining = maxLives;
    }

    public int getMobsRemaining() {
        return mobsRemaining;
    }

    void setMobsRemaining(int count) {
        this.mobsRemaining = count;
    }

    void decrementMobs() {
        if (mobsRemaining > 0) {
            mobsRemaining--;
        }
    }

    /**
     * Reset to a fresh run (floor 1, full lives).
     */
    void reset(int maxLives, int startingFloor) {
        this.state = RunState.FLOOR_ACTIVE;
        this.currentFloor = startingFloor;
        this.livesRemaining = maxLives;
        this.mobsRemaining = 0;
    }
}
