package com.LucaStudios.HytaleDungeons.PlayerData;

import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataManagerTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private PlayerDataManager manager;

    @BeforeAll
    static void loadDatabase() {
        ItemDatabase.load(msg -> {});
    }

    @BeforeEach
    void setUp() {
        manager = new PlayerDataManager(msg -> {});
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Test
    void initPlayer_createsDefaults() {
        manager.initPlayer(PLAYER_ID);

        assertEquals(1, manager.getPlayerLevel(PLAYER_ID));
        assertEquals(0, manager.getCurrentXP(PLAYER_ID));
        assertNotEquals(ItemDefinition.FISTS, manager.getEquippedWeapon(PLAYER_ID));
        assertNull(manager.getEquippedArmor(PLAYER_ID));
        assertNotNull(manager.getEquippedCrossbow(PLAYER_ID));
        assertTrue(manager.getBackpackItems(PLAYER_ID).isEmpty());
    }

    @Test
    void removePlayer_cleansUp() {
        manager.initPlayer(PLAYER_ID);
        manager.removePlayer(PLAYER_ID);

        assertNull(manager.getLoadout(PLAYER_ID));
        assertEquals(1, manager.getPlayerLevel(PLAYER_ID)); // default fallback
    }

    @Test
    void resetPlayer_resetsAllValues() {
        manager.initPlayer(PLAYER_ID);
        manager.grantXP(PLAYER_ID, 200);
        manager.addToBackpack(PLAYER_ID, "mithril_sword");

        manager.resetPlayer(PLAYER_ID);

        assertEquals(1, manager.getPlayerLevel(PLAYER_ID));
        assertEquals(0, manager.getCurrentXP(PLAYER_ID));
        assertTrue(manager.getBackpackItems(PLAYER_ID).isEmpty());
    }

    @Test
    void resetPlayer_withoutInit_createsNew() {
        manager.resetPlayer(PLAYER_ID); // should not throw
        assertEquals(1, manager.getPlayerLevel(PLAYER_ID));
    }

    // ── XP & Leveling ───────────────────────────────────────────────────

    @Test
    void grantXP_levelsUpCorrectly() {
        manager.initPlayer(PLAYER_ID);
        manager.grantXP(PLAYER_ID, 75); // level 1 threshold
        assertEquals(2, manager.getPlayerLevel(PLAYER_ID));
    }

    @Test
    void grantXP_unknownPlayer_noError() {
        manager.grantXP(UUID.randomUUID(), 100); // should not throw
    }

    @Test
    void getXPToNextLevel_matchesFormula() {
        manager.initPlayer(PLAYER_ID);
        // Level 1: 50 + 25*1 = 75
        assertEquals(75, manager.getXPToNextLevel(PLAYER_ID));

        manager.grantXP(PLAYER_ID, 75);
        // Level 2: 50 + 25*2 = 100
        assertEquals(100, manager.getXPToNextLevel(PLAYER_ID));
    }

    // ── Equipped Gear ───────────────────────────────────────────────────

    @Test
    void getEquippedWeapon_returnsIronSword() {
        manager.initPlayer(PLAYER_ID);
        ItemDefinition weapon = manager.getEquippedWeapon(PLAYER_ID);
        assertEquals("iron_sword", weapon.getId());
    }

    @Test
    void getEquippedWeapon_unknownPlayer_returnsFists() {
        assertEquals(ItemDefinition.FISTS, manager.getEquippedWeapon(UUID.randomUUID()));
    }

    @Test
    void getEquippedArmor_unknownPlayer_returnsNull() {
        assertNull(manager.getEquippedArmor(UUID.randomUUID()));
    }

    // ── Backpack ────────────────────────────────────────────────────────

    @Test
    void addToBackpack_addsItem() {
        manager.initPlayer(PLAYER_ID);
        String discarded = manager.addToBackpack(PLAYER_ID, "mithril_sword");
        assertNull(discarded);
        assertEquals(1, manager.getBackpackItems(PLAYER_ID).size());
    }

    @Test
    void equipItem_swapsCorrectly() {
        manager.initPlayer(PLAYER_ID);
        manager.addToBackpack(PLAYER_ID, "mithril_sword");

        assertTrue(manager.equipItem(PLAYER_ID, 0));
        assertEquals("mithril_sword", manager.getEquippedWeapon(PLAYER_ID).getId());
        // iron_sword should now be in backpack
        assertTrue(manager.getBackpackItems(PLAYER_ID).contains("iron_sword"));
    }

    @Test
    void equipItem_invalidSlot_returnsFalse() {
        manager.initPlayer(PLAYER_ID);
        assertFalse(manager.equipItem(PLAYER_ID, 0)); // empty backpack
    }

    @Test
    void equipItem_unknownPlayer_returnsFalse() {
        assertFalse(manager.equipItem(UUID.randomUUID(), 0));
    }
}
