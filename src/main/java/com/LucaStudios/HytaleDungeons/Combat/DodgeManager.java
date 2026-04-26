package com.LucaStudios.HytaleDungeons.Combat;

import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunState;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DodgeManager {

    private static final long DODGE_COOLDOWN_MS = 800L;
    private static final long IFRAME_DURATION_MS = 300L;
    private static final float DODGE_DURATION_S = 0.3f;
    private static final double DODGE_SPEED = 12.0;

    private static final String[] DODGE_ANIMATION_CANDIDATES = {
            "Roll", "SafetyRoll", "DashForward", "RollLeft", "RollRight", "RollBackward"
    };

    private static final class DodgeState {
        long lastDodgeMs;
        long iFrameEndMs;
    }

    private final ConcurrentHashMap<UUID, DodgeState> states = new ConcurrentHashMap<>();
    private final RunStateManager runStateManager;

    public DodgeManager(RunStateManager runStateManager) {
        this.runStateManager = runStateManager;
    }

    public boolean isInIFrames(UUID playerId) {
        if (playerId == null) return false;
        DodgeState state = states.get(playerId);
        if (state == null) return false;
        return System.currentTimeMillis() < state.iFrameEndMs;
    }

    public void removePlayer(UUID playerId) {
        if (playerId != null) states.remove(playerId);
    }

    public void attemptDodge(PlayerRef playerRef, Ref<EntityStore> entityRef, Store<EntityStore> store,
                             double dirX, double dirZ) {
        if (playerRef == null || !playerRef.isValid()) return;
        if (entityRef == null || !entityRef.isValid()) return;

        UUID playerId = playerRef.getUuid();

        RunData runData = runStateManager.getRunData(playerId);
        if (runData == null || runData.getState() != RunState.FLOOR_ACTIVE) return;

        long now = System.currentTimeMillis();
        DodgeState state = states.computeIfAbsent(playerId, k -> new DodgeState());
        if (now < state.lastDodgeMs + DODGE_COOLDOWN_MS) return;

        double mag = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (mag < 0.05) return;

        double nx = dirX / mag;
        double nz = dirZ / mag;
        double vx = nx * DODGE_SPEED;
        double vz = nz * DODGE_SPEED;

        KnockbackComponent kb = new KnockbackComponent();
        kb.setVelocity(new Vector3d(vx, 0.0, vz));
        kb.setVelocityType(ChangeVelocityType.Set);
        kb.setVelocityConfig(new VelocityConfig());
        kb.setDuration(DODGE_DURATION_S);
        kb.setTimer(0f);

        store.addComponent(entityRef, KnockbackComponent.getComponentType(), kb);

        playDodgeAnimation(entityRef, store);

        state.lastDodgeMs = now;
        state.iFrameEndMs = now + IFRAME_DURATION_MS;
    }

    private void playDodgeAnimation(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        ModelComponent mc = store.getComponent(entityRef, ModelComponent.getComponentType());
        if (mc == null) return;
        Model model = mc.getModel();
        if (model == null) return;

        String animId = model.getFirstBoundAnimationId(DODGE_ANIMATION_CANDIDATES);
        if (animId == null) return;

        AnimationUtils.playAnimation(entityRef, AnimationSlot.Action, animId, true, store);
    }
}
