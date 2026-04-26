package com.LucaStudios.HytaleDungeons.Enemies;

public enum Archetype {
    MELEE (20, 5, 0.65, "Goblin_Hermit"),
    RANGED(10, 5, 0.25, "Skeleton_Burnt_Archer"),
    RUSHER(10, 15, 0.10, "Goblin_Hermit");

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
