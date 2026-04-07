package com.LucaStudios.HytaleDungeons.Enemies;

/**
 * Enemy archetypes from the Enemy AI GDD.
 * Each archetype has a base HP, base ATK, and a weight in the spawn roll.
 */
public enum Archetype {
    MELEE(20, 10, 0.60),
    RANGED(12, 14, 0.30),
    RUSHER(10, 40, 0.10);

    public final int baseHp;
    public final int baseAtk;
    public final double weight;

    Archetype(int baseHp, int baseAtk, double weight) {
        this.baseHp = baseHp;
        this.baseAtk = baseAtk;
        this.weight = weight;
    }
}
