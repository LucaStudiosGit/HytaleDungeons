package com.LucaStudios.HytaleDungeons.Loot;

public enum Rarity {
    COMMON(1.0, 45),
    UNCOMMON(1.3, 28),
    RARE(1.6, 15),
    EPIC(2.0, 8),
    LEGENDARY(2.5, 4);

    private final double statMultiplier;
    private final int dropWeight;

    Rarity(double statMultiplier, int dropWeight) {
        this.statMultiplier = statMultiplier;
        this.dropWeight = dropWeight;
    }

    public double getStatMultiplier() {
        return statMultiplier;
    }

    public int getDropWeight() {
        return dropWeight;
    }

    public static Rarity fromString(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return COMMON;
        }
    }
}
