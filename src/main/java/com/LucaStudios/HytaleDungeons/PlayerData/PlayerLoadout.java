package com.LucaStudios.HytaleDungeons.PlayerData;

import com.LucaStudios.HytaleDungeons.Loot.ItemCategory;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Per-player mutable run data: equipped gear, backpack, XP, and level.
 * Owned exclusively by {@link PlayerDataManager}.
 */
public final class PlayerLoadout {

    private final UUID playerId;

    private String equippedWeaponId;
    private String equippedArmorId;
    private String equippedCrossbowId;

    /** Ordered by acquisition time (index 0 = oldest). */
    private final List<String> backpack;
    private final int backpackSize;

    private int playerLevel;
    private int currentXP;

    PlayerLoadout(UUID playerId, int backpackSize,
                  String defaultWeaponId, String defaultArmorId, String defaultCrossbowId) {
        this.playerId = playerId;
        this.backpackSize = backpackSize;
        this.backpack = new ArrayList<>(backpackSize);
        this.equippedWeaponId = defaultWeaponId;
        this.equippedArmorId = defaultArmorId;
        this.equippedCrossbowId = defaultCrossbowId;
        this.playerLevel = 1;
        this.currentXP = 0;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public UUID getPlayerId() {
        return playerId;
    }

    public int getPlayerLevel() {
        return playerLevel;
    }

    public int getCurrentXP() {
        return currentXP;
    }

    public ItemDefinition getEquippedWeapon() {
        if (equippedWeaponId == null) return ItemDefinition.FISTS;
        return ItemDatabase.getInstance().get(equippedWeaponId);
    }

    public ItemDefinition getEquippedArmor() {
        if (equippedArmorId == null) return null;
        return ItemDatabase.getInstance().get(equippedArmorId);
    }

    public ItemDefinition getEquippedCrossbow() {
        if (equippedCrossbowId == null) return null;
        return ItemDatabase.getInstance().get(equippedCrossbowId);
    }

    public String getEquippedWeaponId() {
        return equippedWeaponId;
    }

    public String getEquippedArmorId() {
        return equippedArmorId;
    }

    public String getEquippedCrossbowId() {
        return equippedCrossbowId;
    }

    public List<String> getBackpackItemIds() {
        return List.copyOf(backpack);
    }

    public int getBackpackSize() {
        return backpackSize;
    }

    public int getBackpackUsed() {
        return backpack.size();
    }

    // ── XP & Leveling ───────────────────────────────────────────────────

    /**
     * Grant XP and process any level-ups. Overflow XP carries into next level.
     *
     * @return the number of levels gained (0 if none)
     */
    int grantXP(int amount, int baseXP, int xpPerLevel) {
        if (amount <= 0) return 0;

        currentXP += amount;
        int levelsGained = 0;

        int threshold = calculateXPThreshold(baseXP, xpPerLevel);
        while (currentXP >= threshold) {
            currentXP -= threshold;
            playerLevel++;
            levelsGained++;
            threshold = calculateXPThreshold(baseXP, xpPerLevel);
        }

        return levelsGained;
    }

    /**
     * XP required to reach the next level from the current level.
     */
    int calculateXPThreshold(int baseXP, int xpPerLevel) {
        return baseXP + (xpPerLevel * playerLevel);
    }

    // ── Backpack & Equipping ────────────────────────────────────────────

    /**
     * Add an item to the backpack. If full, auto-discard the weakest item
     * of the same category (or weakest overall if no same-category items).
     *
     * @return the ID of the discarded item, or null if no discard was needed
     */
    String addToBackpack(String itemId) {
        if (backpack.size() < backpackSize) {
            backpack.add(itemId);
            return null;
        }

        // Backpack full — auto-discard lowest baseStat of same category
        ItemDefinition newItem = ItemDatabase.getInstance().get(itemId);
        String discarded = findLowestInBackpack(newItem.getCategory());

        if (discarded == null) {
            // No same-category items — discard lowest overall
            discarded = findLowestInBackpack(null);
        }

        if (discarded != null) {
            backpack.remove(discarded);
            backpack.add(itemId);
        }

        return discarded;
    }

    /**
     * Swap an equipped item with a backpack item. The old equipped item goes
     * to the backpack slot the new item came from (direct swap).
     *
     * @param backpackIndex index in the backpack list
     * @return true if the swap succeeded
     */
    boolean equipFromBackpack(int backpackIndex) {
        if (backpackIndex < 0 || backpackIndex >= backpack.size()) return false;

        String backpackItemId = backpack.get(backpackIndex);
        ItemDefinition backpackItem = ItemDatabase.getInstance().get(backpackItemId);

        String oldEquippedId;
        switch (backpackItem.getCategory()) {
            case WEAPON -> {
                oldEquippedId = equippedWeaponId;
                equippedWeaponId = backpackItemId;
            }
            case ARMOR -> {
                oldEquippedId = equippedArmorId;
                equippedArmorId = backpackItemId;
            }
            case CROSSBOW -> {
                oldEquippedId = equippedCrossbowId;
                equippedCrossbowId = backpackItemId;
            }
            default -> {
                return false;
            }
        }

        // Direct swap: old equipped item takes the backpack slot
        if (oldEquippedId != null) {
            backpack.set(backpackIndex, oldEquippedId);
        } else {
            backpack.remove(backpackIndex);
        }

        return true;
    }

    /**
     * Find the item with the lowest baseStat in the backpack, optionally
     * filtered by category. Returns the item ID, or null if no match.
     */
    private String findLowestInBackpack(ItemCategory category) {
        String lowestId = null;
        int lowestStat = Integer.MAX_VALUE;

        for (String id : backpack) {
            ItemDefinition item = ItemDatabase.getInstance().get(id);
            if (category != null && item.getCategory() != category) continue;
            if (item.getBaseStat() < lowestStat) {
                lowestStat = item.getBaseStat();
                lowestId = id;
            }
        }

        return lowestId;
    }

    // ── Reset ───────────────────────────────────────────────────────────

    /**
     * Reset to new-run defaults.
     */
    void reset(String defaultWeaponId, String defaultArmorId, String defaultCrossbowId) {
        this.equippedWeaponId = defaultWeaponId;
        this.equippedArmorId = defaultArmorId;
        this.equippedCrossbowId = defaultCrossbowId;
        this.backpack.clear();
        this.playerLevel = 1;
        this.currentXP = 0;
    }
}
