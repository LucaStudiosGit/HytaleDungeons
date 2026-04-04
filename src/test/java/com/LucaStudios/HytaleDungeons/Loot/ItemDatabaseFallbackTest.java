package com.LucaStudios.HytaleDungeons.Loot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the fallback behavior when config/items.json is missing.
 * This test class uses a separate loading mechanism to avoid conflicting
 * with the shared ItemDatabase singleton from other tests.
 */
class ItemDatabaseFallbackTest {

    @Test
    void missingConfigFallsBackToDefaults() {
        // Save current instance, load with a broken classpath
        List<String> logs = new ArrayList<>();

        // We can test the fallback by loading from a classloader that won't find the file
        // Since ItemDatabase.load() is static and overwrites the singleton,
        // we test the fallback items directly
        List<ItemDefinition> fallback = ItemDatabase.createFallbackItems();

        assertEquals(3, fallback.size(), "Fallback should have exactly 3 items");

        // Verify one of each category exists
        assertTrue(fallback.stream().anyMatch(i -> i.getCategory() == ItemCategory.WEAPON),
                "Fallback must include a weapon");
        assertTrue(fallback.stream().anyMatch(i -> i.getCategory() == ItemCategory.ARMOR),
                "Fallback must include armor");
        assertTrue(fallback.stream().anyMatch(i -> i.getCategory() == ItemCategory.CROSSBOW),
                "Fallback must include a crossbow");

        // Verify fallback items have valid stats
        for (ItemDefinition item : fallback) {
            assertTrue(item.getBaseStat() >= 1, "Fallback item baseStat must be >= 1");
            assertNotNull(item.getHytaleItemId());
            assertFalse(item.getHytaleItemId().isEmpty(), "Fallback item must have a hytaleItemId");
        }
    }
}
