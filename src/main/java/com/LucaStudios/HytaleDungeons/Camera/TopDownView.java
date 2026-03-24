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

    public static void enable(PlayerRef playerRef) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2f;
        settings.rotationLerpSpeed = 0.2f;
        settings.distance = 8.0f;
        settings.displayCursor = true;
        settings.allowPitchControls = false;

        settings.isFirstPerson = false;

//        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        Direction cameraDirection = new Direction(
                (float) Math.toRadians(45f),  // yaw
                (float) Math.toRadians(-35f), // pitch
                0f                            // roll
        );
//        Direction fixedPlayerFacing = new Direction(
//                (float) Math.toRadians(45f),  // face camera-forward direction
//                0f,
//                0f
//        );

        settings.rotation = cameraDirection;
//        settings.movementForceRotation = fixedPlayerFacing;

//        settings.mouseInputType = MouseInputType.LookAtPlane;
//        settings.planeNormal = new Vector3f(0f, 1f, 0f);
//        settings.applyLookType = ApplyLookType.LocalPlayerLookOrientation;

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
