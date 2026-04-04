# System Design: Camera System

*Created: 2026-04-01*
*Status: Draft*
*Systems Index: design/gdd/systems-index.md (#2)*

---

## 1. Overview

The Camera System provides a fixed isometric top-down view that follows the player.
It does not rotate, zoom, or allow player control. The camera is set once on player
join and stays locked. The existing `TopDownView` class is nearly complete — only
tuning is needed.

---

## 2. Player Fantasy

You always have a clear, consistent view of the dungeon around you. You can see
enemies approaching, plan your route through rooms, and never fight the camera.
It just works.

---

## 3. Detailed Rules

### Camera Configuration

| Parameter | Value | Notes |
|-----------|-------|-------|
| Yaw | 45deg | Isometric angle |
| Pitch | -35deg | Looking down at the action |
| Distance | 8.0 | Units from player. Adjustable per floor size if needed. |
| Position Lerp | 0.2 | Smooth follow speed |
| Rotation Lerp | 0.2 | Smooth rotation (unused since rotation is fixed) |
| First Person | false | Always third-person isometric |
| Cursor Visible | true | Needed for crossbow aiming |
| Pitch Controls | false | Player cannot rotate camera |

### Behavior

- Camera is enabled via `TopDownView.enable(playerRef)` on `PlayerReadyEvent`
- Camera follows the player position with smooth lerp
- Camera does not rotate — yaw and pitch are fixed
- `faceDirection(playerRef, yaw)` is called by combat system to rotate the
  player model when shooting (camera stays fixed, only player rotates)
- `reset(playerRef)` is available but not used during normal gameplay

### Camera View Type

Uses Hytale's `ClientCameraView.Custom` with `ServerCameraSettings`. The camera
uses `PositionDistanceOffsetType.DistanceOffset` to maintain distance from player.

---

## 4. Formulas

### Camera Position (handled by Hytale engine)

```
cameraPosition = playerPosition + offset
offset = sphericalToCartesian(distance=8.0, yaw=45deg, pitch=-35deg)
```

The Hytale engine handles the lerp and offset calculation internally via
`ServerCameraSettings`. No custom math needed.

---

## 5. Edge Cases

- **Player teleports (floor transition)**: Camera lerp will smoothly catch up.
  If the teleport distance is large, the lerp may feel slow — consider calling
  `enable()` again to snap the camera.
- **Player dies and respawns**: Camera continues following — no reset needed.
- **Player disconnects**: Camera state is per-player, cleaned up by Hytale.
- **Multiple players (co-op v2)**: Each player has their own camera. No split-screen.

---

## 6. Dependencies

- **Depended on by**: Player Controller (WASD directions are relative to camera yaw),
  Combat System (`faceDirection` used for crossbow aiming)
- **Depends on**: Player Controller (camera follows player position)
- **Hytale API dependency**: `SetServerCamera` packet, `ServerCameraSettings`,
  `ClientCameraView.Custom`
- **Existing implementation**: `TopDownView.java` — nearly complete

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `CAMERA_DISTANCE` | 8.0 | 5.0 - 15.0 | How far the camera is from the player. Larger = see more, smaller = more intimate. |
| `CAMERA_YAW` | 45deg | Fixed for MVP | Isometric angle. Changing this requires updating Player Controller input rotation. |
| `CAMERA_PITCH` | -35deg | -25 to -60 | Steeper = more top-down, shallower = more side-view. |
| `POSITION_LERP_SPEED` | 0.2 | 0.05 - 1.0 | Camera follow smoothness. 1.0 = instant snap. |

---

## 8. Acceptance Criteria

- [ ] Camera activates on player join with correct isometric angle
- [ ] Camera follows player smoothly during WASD movement
- [ ] Camera does not rotate when player moves or looks around
- [ ] Cursor is visible on screen (needed for crossbow aiming)
- [ ] `faceDirection()` rotates the player model without moving the camera
- [ ] Camera distance of 8.0 provides adequate dungeon room visibility
- [ ] Camera feels correct after floor transitions (no stuck/offset camera)
