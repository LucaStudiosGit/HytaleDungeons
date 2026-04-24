package com.LucaStudios.HytaleDungeons.Loot;

/**
 * One rolled loot card on the between-floors screen: an item template paired
 * with the per-instance level it was rolled at. The same item id can appear
 * multiple times in a run with different levels.
 */
public record LootOffer(ItemDefinition item, int level) {
}
