package com.LucaStudios.HytaleDungeons.Loot;

public final class ItemDefinition {

    public static final ItemDefinition FISTS = new ItemDefinition(
            "fists", ItemCategory.WEAPON, "Fists", "", 1, Rarity.COMMON, "", 400
    );

    private final String id;
    private final ItemCategory category;
    private final String displayName;
    private final String hytaleItemId;
    private final int baseStat;
    private final Rarity rarity;
    private final String description;
    private final int cooldownMs;

    public ItemDefinition(String id, ItemCategory category, String displayName,
                          String hytaleItemId, int baseStat, Rarity rarity,
                          String description, int cooldownMs) {
        this.id = id;
        this.category = category;
        this.displayName = displayName;
        this.hytaleItemId = hytaleItemId;
        this.baseStat = Math.max(1, baseStat);
        this.rarity = rarity;
        this.description = description;
        this.cooldownMs = cooldownMs;
    }

    public String getId() {
        return id;
    }

    public ItemCategory getCategory() {
        return category;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHytaleItemId() {
        return hytaleItemId;
    }

    public int getBaseStat() {
        return baseStat;
    }

    public Rarity getRarity() {
        return rarity;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Attack cooldown in milliseconds. Defines how fast this weapon can attack.
     */
    public int getCooldownMs() {
        return cooldownMs;
    }

    /**
     * Calculates the effective stat value scaled to a player's level.
     *
     * @param playerLevel the player's current level
     * @param levelScaleFactor scaling factor per level (default 0.1)
     * @return the effective stat, floored to an integer
     */
    public int getEffectiveStat(int playerLevel, double levelScaleFactor) {
        double scaled = baseStat * rarity.getStatMultiplier() * (1.0 + levelScaleFactor * playerLevel);
        return (int) scaled;
    }

    /**
     * Calculates the effective stat with the default level scale factor of 0.1.
     */
    public int getEffectiveStat(int playerLevel) {
        return getEffectiveStat(playerLevel, ItemDatabase.DEFAULT_LEVEL_SCALE_FACTOR);
    }
}
