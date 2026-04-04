package com.LucaStudios.HytaleDungeons.Health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerHealthTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private PlayerHealth health;

    @BeforeEach
    void setUp() {
        health = new PlayerHealth(PLAYER_ID, 200);
    }

    // --- Initial state ---

    @Test
    void startsAtMaxHP() {
        assertEquals(200, health.getCurrentHP());
        assertEquals(200, health.getMaxHP());
        assertFalse(health.isDead());
    }

    // --- Damage ---

    @Test
    void takeDamageReducesHP() {
        int actual = health.takeDamage(30, 0);
        assertEquals(30, actual);
        assertEquals(170, health.getCurrentHP());
    }

    @Test
    void armorReducesDamage() {
        // GDD example: 30 raw, 6 armor → 24 actual
        int actual = health.takeDamage(30, 6);
        assertEquals(24, actual);
        assertEquals(176, health.getCurrentHP());
    }

    @Test
    void minimumDamageIsOne() {
        // 5 raw, 10 armor → max(1, 5-10) = 1
        int actual = health.takeDamage(5, 10);
        assertEquals(1, actual);
        assertEquals(199, health.getCurrentHP());
    }

    @Test
    void damageCannotGoNegative_HPFloorsAtZero() {
        int actual = health.takeDamage(999, 0);
        assertEquals(999, actual); // actual damage applied (HP floors at 0)
        assertEquals(0, health.getCurrentHP());
        assertTrue(health.isDead());
    }

    @Test
    void zeroDamageDealsNothing() {
        int actual = health.takeDamage(0, 0);
        assertEquals(0, actual);
        assertEquals(200, health.getCurrentHP());
    }

    @Test
    void negativeDamageDealsNothing() {
        int actual = health.takeDamage(-5, 0);
        assertEquals(0, actual);
        assertEquals(200, health.getCurrentHP());
    }

    @Test
    void damageOnDeadPlayerReturnsZero() {
        health.takeDamage(200, 0);
        assertTrue(health.isDead());
        int actual = health.takeDamage(50, 0);
        assertEquals(0, actual);
        assertEquals(0, health.getCurrentHP());
    }

    @Test
    void multipleDamageSourcesProcessSequentially() {
        health.takeDamage(100, 0);
        assertEquals(100, health.getCurrentHP());
        health.takeDamage(80, 0);
        assertEquals(20, health.getCurrentHP());
        health.takeDamage(50, 0);
        assertEquals(0, health.getCurrentHP());
        assertTrue(health.isDead());
        // Further damage ignored
        assertEquals(0, health.takeDamage(10, 0));
    }

    // --- Potion ---

    @Test
    void potionHealsHP() {
        health.takeDamage(100, 0); // 100 HP
        int healed = health.usePotion(50, 8000);
        assertEquals(50, healed);
        assertEquals(150, health.getCurrentHP());
    }

    @Test
    void potionCapsAtMaxHP() {
        health.takeDamage(30, 0); // 170 HP
        int healed = health.usePotion(50, 8000);
        assertEquals(30, healed); // only 30 needed to reach max
        assertEquals(200, health.getCurrentHP());
    }

    @Test
    void potionAtFullHPWastedButTriggersCooldown() {
        int healed = health.usePotion(50, 8000);
        assertEquals(0, healed);
        assertEquals(200, health.getCurrentHP());
        assertTrue(health.isPotionOnCooldown());
    }

    @Test
    void potionCooldownBlocksUse() {
        health.usePotion(50, 8000);
        assertTrue(health.isPotionOnCooldown());
        assertTrue(health.getPotionCooldownRemainingMs() > 0);
    }

    @Test
    void potionCooldownRemainingNeverNegative() {
        // No potion used — cooldown should be 0
        assertFalse(health.isPotionOnCooldown());
        assertEquals(0, health.getPotionCooldownRemainingMs());
    }

    // --- Reset ---

    @Test
    void resetRestoresHPToMax() {
        health.takeDamage(150, 0);
        assertEquals(50, health.getCurrentHP());
        health.reset();
        assertEquals(200, health.getCurrentHP());
        assertFalse(health.isDead());
    }

    @Test
    void resetClearsPotionCooldown() {
        health.usePotion(50, 8000);
        assertTrue(health.isPotionOnCooldown());
        health.reset();
        assertFalse(health.isPotionOnCooldown());
    }

    @Test
    void resetAfterDeathRestoresFullHealth() {
        health.takeDamage(200, 0);
        assertTrue(health.isDead());
        health.reset();
        assertEquals(200, health.getCurrentHP());
        assertFalse(health.isDead());
    }
}
