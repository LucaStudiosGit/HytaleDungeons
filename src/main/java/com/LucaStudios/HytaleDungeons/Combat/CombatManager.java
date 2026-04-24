package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatManager {

    private final RunStateManager runStateManager;

    private final ConcurrentHashMap<UUID, Long> lastMeleeTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRangedTime = new ConcurrentHashMap<>();

    public CombatManager(RunStateManager runStateManager) {
        this.runStateManager = runStateManager;
    }

    public boolean canMeleeAttack(UUID playerId, ItemDefinition weapon) {
        return canAttack(playerId, weapon, lastMeleeTime);
    }

    public boolean canRangedAttack(UUID playerId, ItemDefinition crossbow) {
        return canAttack(playerId, crossbow, lastRangedTime);
    }

    public void recordMeleeAttack(UUID playerId) {
        lastMeleeTime.put(playerId, System.currentTimeMillis());
    }

    public void recordRangedAttack(UUID playerId) {
        lastRangedTime.put(playerId, System.currentTimeMillis());
    }

    private boolean canAttack(UUID playerId, ItemDefinition item,
                              ConcurrentHashMap<UUID, Long> cooldownMap) {
        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastTime = cooldownMap.get(playerId);
        return lastTime == null || (now - lastTime) >= item.getCooldownMs();
    }

    public int calculateMeleeDamage(ItemDefinition weapon, int itemLevel) {
        return weapon.getEffectiveStat(itemLevel);
    }

    public int calculateRangedDamage(ItemDefinition crossbow, int itemLevel) {
        return crossbow.getEffectiveStat(itemLevel);
    }

    public void removePlayer(UUID playerId) {
        lastMeleeTime.remove(playerId);
        lastRangedTime.remove(playerId);
    }
}
