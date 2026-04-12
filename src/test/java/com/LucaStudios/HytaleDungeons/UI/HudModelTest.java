package com.LucaStudios.HytaleDungeons.UI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudModelTest {

    private static HudModel model(int hp, int maxHp, long cdMs, int arrows, int level, int lives) {
        return new HudModel(hp, maxHp, cdMs, arrows, level, lives);
    }


    @Test
    void formatPotionShowsKeybindWhenReady() {
        assertEquals("4", model(100, 100, 0L, 30, 1, 3).formatPotion());
    }

    @Test
    void formatPotionShowsKeybindWhenCooldownNegative() {
        assertEquals("4", model(100, 100, -100L, 30, 1, 3).formatPotion());
    }

    @Test
    void formatPotionShowsCeilSecondsCountdown() {
        // 8000ms → 8, 7001ms → 8 (ceil), 1ms → 1
        assertEquals("8", model(100, 100, 8000L, 30, 1, 3).formatPotion());
        assertEquals("8", model(100, 100, 7001L, 30, 1, 3).formatPotion());
        assertEquals("1", model(100, 100, 1L, 30, 1, 3).formatPotion());
    }

    @Test
    void formatArrowsIsRawNumber() {
        assertEquals("30", model(100, 100, 0, 30, 1, 3).formatArrows());
        assertEquals("0", model(100, 100, 0, 0, 1, 3).formatArrows());
    }

    @Test
    void formatLevelPrependsLv() {
        assertEquals("Lv 1", model(100, 100, 0, 30, 1, 3).formatLevel());
        assertEquals("Lv 42", model(100, 100, 0, 30, 42, 3).formatLevel());
    }

    @Test
    void formatLivesIsRawCount() {
        assertEquals("3", model(100, 100, 0, 30, 1, 3).formatLives());
    }

    @Test
    void formatLivesClampsNegativeToZero() {
        assertEquals("0", model(0, 100, 0, 30, 1, -1).formatLives());
    }

    @Test
    void placeholderArrowCountIsPinned() {
        assertEquals(30, HudModel.PLACEHOLDER_ARROW_COUNT);
    }
}
