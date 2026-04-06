package com.LucaStudios.HytaleDungeons.Camera;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector2f;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class TopDownView {

    private static ServerCameraSettings buildBaseSettings() {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2f;
        settings.rotationLerpSpeed = 0.2f;
        settings.distance = 5.0f;
        settings.displayCursor = true;
        settings.allowPitchControls = false;
        settings.isFirstPerson = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(
                (float) Math.toRadians(165f),   // yaw — offset 15° left of +Z
                (float) Math.toRadians(-35f), // pitch
                0f                            // roll
        );
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0f, 1f, 0f);
//        settings.sendMouseMotion = true;
//        settings.lookMultiplier = new Vector2f(0f, 0f);
//        settings.movementForceRotationType = MovementForceRotationType.CameraRotation;
//        settings.movementForceRotation = new Direction(
//                (float) Math.toRadians(45.0f),
//                0.0f,
//                0.0f
//        );

        return settings;
    }

    public static void enable(PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, buildBaseSettings())
        );
    }
}
