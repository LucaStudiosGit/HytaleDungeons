package com.LucaStudios.HytaleDungeons.PlayerData;

import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLoadoutTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final int BACKPACK_SIZE = 9;
    private static final String DEFAULT_WEAPON = "iron_sword";
    private static final String DEFAULT_CROSSBOW = "iron_crossbow";
    private static final int BASE_XP = 50;
    private static final int XP_PER_LEVEL = 25;

    private PlayerLoadout loadout;

    @BeforeAll
    static void loadDatabase() {
        ItemDatabase.load(msg -> {});
    }

    @BeforeEach
    void setUp() {
        loadout = new PlayerLoadout(PLAYER_ID, BACKPACK_SIZE, DEFAULT_WEAPON, null, DEFAULT_CROSSBOW);
    }

    // ── Initial State ────────────────────────────────────────────────────

    @Test
    void initialState_level1_zeroXP() {
        assertEquals(1, loadout.getPlayerLevel());
        assertEquals(0, loadout.getCurrentXP());
    }

    @Test
    void initialState_defaultWeaponEquipped() {
        assertEquals("iron_sword", loadout.getEquippedWeaponId());
        assertNotNull(loadout.getEquippedWeapon());
        assertNotEquals(ItemDefinition.FISTS, loadout.getEquippedWeapon());
    }

    @Test
    void initialState_noArmorEquipped() {
        assertNull(loadout.getEquippedArmorId());
        assertNull(loadout.getEquippedArmor());
    }

    @Test
    void initialState_defaultCrossbowEquipped() {
        assertEquals("iron_crossbow", loadout.getEquippedCrossbowId());
        assertNotNull(loadout.getEquippedCrossbow());
    }

    @Test
    void initialState_emptyBackpack() {
        assertEquals(0, loadout.getBackpackUsed());
        assertTrue(loadout.getBackpackItemIds().isEmpty());
    }

    // ── XP & Leveling ───────────────────────────────────────────────────

    @Test
    void grantXP_belowThreshold_noLevelUp() {
        // Level 1: threshold = 50 + 25*1 = 75
        int levels = loadout.grantXP(50, BASE_XP, XP_PER_LEVEL);
        assertEquals(0, levels);
        assertEquals(1, loadout.getPlayerLevel());
        assertEquals(50, loadout.getCurrentXP());
    }

    @Test
    void grantXP_exactThreshold_levelsUp() {
        // Level 1 threshold = 75
        int levels = loadout.grantXP(75, BASE_XP, XP_PER_LEVEL);
        assertEquals(1, levels);
        assertEquals(2, loadout.getPlayerLevel());
        assertEquals(0, loadout.getCurrentXP());
    }

    @Test
    void grantXP_overflowCarries() {
        // Level 1 threshold = 75, grant 100 → level 2 with 25 carry-over
        int levels = loadout.grantXP(100, BASE_XP, XP_PER_LEVEL);
        assertEquals(1, levels);
        assertEquals(2, loadout.getPlayerLevel());
        assertEquals(25, loadout.getCurrentXP());
    }

    @Test
    void grantXP_multipleLevetUps() {
        // Level 1 threshold = 75, level 2 threshold = 100 → 175 total for 2 levels
        int levels = loadout.grantXP(175, BASE_XP, XP_PER_LEVEL);
        assertEquals(2, levels);
        assertEquals(3, loadout.getPlayerLevel());
        assertEquals(0, loadout.getCurrentXP());
    }

    @Test
    void grantXP_zeroAmount_noEffect() {
        int levels = loadout.grantXP(0, BASE_XP, XP_PER_LEVEL);
        assertEquals(0, levels);
        assertEquals(0, loadout.getCurrentXP());
    }

    @Test
    void grantXP_negativeAmount_noEffect() {
        int levels = loadout.grantXP(-10, BASE_XP, XP_PER_LEVEL);
        assertEquals(0, levels);
        assertEquals(0, loadout.getCurrentXP());
    }

    @Test
    void xpThreshold_formula() {
        // Level 1: 50 + 25*1 = 75
        assertEquals(75, loadout.calculateXPThreshold(BASE_XP, XP_PER_LEVEL));

        // Level up to 2, then check: 50 + 25*2 = 100
        loadout.grantXP(75, BASE_XP, XP_PER_LEVEL);
        assertEquals(100, loadout.calculateXPThreshold(BASE_XP, XP_PER_LEVEL));

        // Level up to 5, threshold = 50 + 25*5 = 175
        loadout.grantXP(100 + 125 + 150, BASE_XP, XP_PER_LEVEL); // 375 → levels 3, 4, 5
        assertEquals(5, loadout.getPlayerLevel());
        assertEquals(175, loadout.calculateXPThreshold(BASE_XP, XP_PER_LEVEL));
    }

    // ── Backpack ────────────────────────────────────────────────────────

    @Test
    void addToBackpack_whenNotFull_addsItem() {
        String discarded = loadout.addToBackpack("wood_sword");
        assertNull(discarded);
        assertEquals(1, loadout.getBackpackUsed());
        assertEquals("wood_sword", loadout.getBackpackItemIds().get(0));
    }

    @Test
    void addToBackpack_fillsToCapacity() {
        for (int i = 0; i < BACKPACK_SIZE; i++) {
            loadout.addToBackpack("iron_sword");
        }
        assertEquals(BACKPACK_SIZE, loadout.getBackpackUsed());
    }

    @Test
    void addToBackpack_whenFull_autoDiscardsLowestSameCategory() {
        // Fill with weapons of different baseStat
        loadout.addToBackpack("wood_sword");      // baseStat 3
        loadout.addToBackpack("trork_sword");     // baseStat 5
        loadout.addToBackpack("iron_sword");      // baseStat 8
        loadout.addToBackpack("mithril_sword");   // baseStat 12
        loadout.addToBackpack("copper_axe");      // baseStat 4
        loadout.addToBackpack("trork_axe");       // baseStat 6
        loadout.addToBackpack("iron_axe");        // baseStat 10
        loadout.addToBackpack("adamantite_axe");  // baseStat 14
        loadout.addToBackpack("daggers");         // baseStat 6
        assertEquals(BACKPACK_SIZE, loadout.getBackpackUsed());

        // Add another weapon — should discard wood_sword (baseStat 3, lowest weapon)
        String discarded = loadout.addToBackpack("mace"); // baseStat 11
        assertEquals("wood_sword", discarded);
        assertEquals(BACKPACK_SIZE, loadout.getBackpackUsed());
        assertFalse(loadout.getBackpackItemIds().contains("wood_sword"));
        assertTrue(loadout.getBackpackItemIds().contains("mace"));
    }

    // ── Equip from Backpack ─────────────────────────────────────────────

    @Test
    void equipFromBackpack_swapsWithEquippedSlot() {
        loadout.addToBackpack("mithril_sword");
        assertTrue(loadout.equipFromBackpack(0));

        assertEquals("mithril_sword", loadout.getEquippedWeaponId());
        // Old weapon (iron_sword) should now be in backpack
        assertTrue(loadout.getBackpackItemIds().contains("iron_sword"));
    }

    @Test
    void equipFromBackpack_armorSlot() {
        loadout.addToBackpack("iron_armor");
        assertTrue(loadout.equipFromBackpack(0));

        assertEquals("iron_armor", loadout.getEquippedArmorId());
        // No old armor → backpack slot removed (was null)
        assertEquals(0, loadout.getBackpackUsed());
    }

    @Test
    void equipFromBackpack_invalidIndex_returnsFalse() {
        assertFalse(loadout.equipFromBackpack(-1));
        assertFalse(loadout.equipFromBackpack(0)); // empty backpack
        assertFalse(loadout.equipFromBackpack(100));
    }

    // ── Reset ───────────────────────────────────────────────────────────

    @Test
    void reset_clearsEverything() {
        // Modify state
        loadout.grantXP(200, BASE_XP, XP_PER_LEVEL);
        loadout.addToBackpack("mithril_sword");
        loadout.addToBackpack("iron_armor");

        // Reset
        loadout.reset(DEFAULT_WEAPON, null, DEFAULT_CROSSBOW);

        assertEquals(1, loadout.getPlayerLevel());
        assertEquals(0, loadout.getCurrentXP());
        assertEquals(DEFAULT_WEAPON, loadout.getEquippedWeaponId());
        assertNull(loadout.getEquippedArmorId());
        assertEquals(DEFAULT_CROSSBOW, loadout.getEquippedCrossbowId());
        assertEquals(0, loadout.getBackpackUsed());
    }
}
