package com.LucaStudios.HytaleDungeons.PlayerData;

import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages per-player run data: equipped gear, backpack, XP, and level.
 * Thread-safe for concurrent access from world threads.
 *
 * @see PlayerLoadout
 */
public final class PlayerDataManager {

    /** Base component of the XP curve formula. */
    public static final int BASE_XP = 50;

    /** Additional XP required per level. */
    public static final int XP_PER_LEVEL = 25;

    /** Maximum backpack slots. */
    public static final int BACKPACK_SIZE = 9;

    /** Default equipped item IDs for a new run. */
    public static final String DEFAULT_WEAPON = "wood_sword";
    public static final String DEFAULT_CROSSBOW = "iron_crossbow";
    public static final String DEFAULT_ARMOR = null; // no armor

    private final ConcurrentHashMap<UUID, PlayerLoadout> players = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public PlayerDataManager(Consumer<String> logger) {
        this.logger = logger;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Initialize player data for a new player joining the server.
     */
    public void initPlayer(UUID playerId) {
        players.put(playerId, new PlayerLoadout(
                playerId, BACKPACK_SIZE, DEFAULT_WEAPON, DEFAULT_ARMOR, DEFAULT_CROSSBOW));
    }

    /**
     * Reset player data for a new run (keeps the player entry, resets all values).
     */
    public void resetPlayer(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) {
            initPlayer(playerId);
            return;
        }
        loadout.reset(DEFAULT_WEAPON, DEFAULT_ARMOR, DEFAULT_CROSSBOW);
    }

    /**
     * Remove all data for a disconnected player.
     */
    public void removePlayer(UUID playerId) {
        players.remove(playerId);
    }

    // ── XP & Leveling ───────────────────────────────────────────────────

    /**
     * Grant XP to a player from an enemy kill. Processes level-ups automatically.
     *
     * @param playerId the player who earned the XP
     * @param amount XP to award (must be positive)
     */
    public void grantXP(UUID playerId, int amount) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) return;

        loadout.grantXP(amount, BASE_XP, XP_PER_LEVEL);
    }

    /**
     * Get the player's current level.
     */
    public int getPlayerLevel(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getPlayerLevel() : 1;
    }

    /**
     * Get the player's current XP toward the next level.
     */
    public int getCurrentXP(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getCurrentXP() : 0;
    }

    /**
     * Get the XP required to reach the next level from the player's current level.
     */
    public int getXPToNextLevel(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) return BASE_XP + XP_PER_LEVEL;
        return loadout.calculateXPThreshold(BASE_XP, XP_PER_LEVEL);
    }

    // ── Equipped Gear ───────────────────────────────────────────────────

    /**
     * Get the player's equipped weapon definition. Returns FISTS if none equipped.
     */
    public ItemDefinition getEquippedWeapon(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedWeapon() : ItemDefinition.FISTS;
    }

    /**
     * Get the player's equipped armor definition. Returns null if none equipped.
     */
    public ItemDefinition getEquippedArmor(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedArmor() : null;
    }

    /**
     * Get the player's equipped crossbow definition. Returns null if none equipped.
     */
    public ItemDefinition getEquippedCrossbow(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedCrossbow() : null;
    }

    // ── Backpack ────────────────────────────────────────────────────────

    /**
     * Add an item to the player's backpack. Auto-discards the weakest item
     * if the backpack is full.
     *
     * @return the discarded item ID, or null if no discard was needed
     */
    public String addToBackpack(UUID playerId, String itemId) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) return null;

        return loadout.addToBackpack(itemId);
    }

    /**
     * Swap an equipped item with a backpack item (direct swap).
     *
     * @param backpackSlot index in the backpack
     * @return true if the swap succeeded
     */
    public boolean equipItem(UUID playerId, int backpackSlot) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) return false;
        return loadout.equipFromBackpack(backpackSlot);
    }

    /**
     * Replace the equipped item in the slot matching the given item's category,
     * recording its rolled instance level. The previous equipped item is
     * discarded (not moved to the backpack). Callers are responsible for
     * mirroring the change to the Hytale hotbar.
     */
    public void replaceEquippedForCategory(UUID playerId, String itemId, int itemLevel) {
        PlayerLoadout loadout = players.get(playerId);
        if (loadout == null) return;
        loadout.setEquippedForCategory(itemId, itemLevel);
    }

    /** Get the level of the player's currently equipped weapon (1 if none). */
    public int getEquippedWeaponLevel(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedWeaponLevel() : 1;
    }

    /** Get the level of the player's currently equipped armor (1 if none). */
    public int getEquippedArmorLevel(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedArmorLevel() : 1;
    }

    /** Get the level of the player's currently equipped crossbow (1 if none). */
    public int getEquippedCrossbowLevel(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getEquippedCrossbowLevel() : 1;
    }

    /**
     * Get a snapshot of the player's backpack item IDs.
     */
    public List<String> getBackpackItems(UUID playerId) {
        PlayerLoadout loadout = players.get(playerId);
        return loadout != null ? loadout.getBackpackItemIds() : List.of();
    }

    /**
     * Get the raw PlayerLoadout for read access. Returns null if player not tracked.
     */
    public PlayerLoadout getLoadout(UUID playerId) {
        return players.get(playerId);
    }
}
