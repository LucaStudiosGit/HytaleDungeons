package com.LucaStudios.HytaleDungeons.Camera;

import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class TopDownView {

    private static ServerCameraSettings buildBaseSettings() {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2f;
        settings.rotationLerpSpeed = 0.2f;
        settings.distance = 8.0f;
        settings.displayCursor = true;
        settings.allowPitchControls = false;
        settings.isFirstPerson = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(
                (float) Math.toRadians(45f),  // yaw
                (float) Math.toRadians(-35f), // pitch
                0f                            // roll
        );
        return settings;
    }

    public static void enable(PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, buildBaseSettings())
        );
    }

    public static void faceDirection(PlayerRef playerRef, float yaw) {
        ServerCameraSettings settings = buildBaseSettings();
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.movementForceRotation = new Direction(yaw, 0f, 0f);
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, settings)
        );
    }

    public static void reset(PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, false, null)
        );
    }
}
