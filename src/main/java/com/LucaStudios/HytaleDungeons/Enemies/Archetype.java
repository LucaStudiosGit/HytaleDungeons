package com.LucaStudios.HytaleDungeons.Enemies;

/**
 * Enemy archetypes from the Enemy AI GDD.
 * Each archetype has a base HP, base ATK, and a weight in the spawn roll.
 */
public enum Archetype {
    MELEE(20, 10, 0.60, "Goblin_Hermit"),
    RANGED(12, 14, 0.30, "Skeleton_Burnt_Archer"),
    RUSHER(20, 40, 0.10, "Goblin_Hermit");

    public final int baseHp;
    public final int baseAtk;
    public final double weight;
    public final String entityID;

    Archetype(int baseHp, int baseAtk, double weight, String entityID) {
        this.baseHp = baseHp;
        this.baseAtk = baseAtk;
        this.weight = weight;
        this.entityID = entityID;
    }
}
