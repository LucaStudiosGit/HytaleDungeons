# System Design: Player Controller

*Created: 2026-04-01*
*Status: Draft*
*Systems Index: design/gdd/systems-index.md (#1)*

---

## 1. Overview

The Player Controller handles WASD movement with a fixed isometric camera. The
player faces the direction they move. No click-to-move, no jumping, no sprinting
for MVP. This replaces the existing `ClickToMoveHandler` which is scrapped.

---

## 2. Player Fantasy

Instant, responsive movement. You press a direction and your character is already
there. No delay, no momentum buildup — just crisp, arcade-style control that
lets you focus on combat and exploration.

---

## 3. Detailed Rules

### Input Mapping

| Input | Action |
|-------|--------|
| W | Move forward (relative to fixed camera) |
| A | Move left (relative to fixed camera) |
| S | Move backward (relative to fixed camera) |
| D | Move right (relative to fixed camera) |
| W+A, W+D, etc. | Diagonal movement (normalized to prevent faster diagonal speed) |
| No input | Stop moving, idle animation |

### Camera-Relative Movement

The camera is fixed at 45deg yaw, -35deg pitch (see Camera System GDD). WASD
directions are relative to this camera orientation:

- W = move toward top-left of screen (camera forward projected onto ground)
- S = move toward bottom-right of screen
- A = move toward bottom-left of screen
- D = move toward top-right of screen

The movement direction is calculated by rotating the input vector by the camera's
yaw angle (45deg).

### Player Rotation

- Player model rotates to face the movement direction
- Rotation is instant (no lerp for MVP — snappy arcade feel)
- When the player stops moving, they hold their last facing direction

### Movement Implementation

**Critical API risk**: The exact Hytale API for reading keyboard input is unverified.
Possible approaches (investigate in priority order):

1. **MovementManager / player input events**: Hytale may expose WASD key states
   through `MovementManager` or a dedicated keyboard input event. Check
   `com.hypixel.hytale.server.core.event.events.player` for input-related events.
2. **Client packet interception**: If no event exists, intercept client movement
   packets via `GenericPacketHandler` (same pattern as `InventoryOpenDisabler`).
3. **Velocity override on tick**: If direct input is unavailable, override
   player velocity each tick based on observed position deltas. Least desirable.

**Before implementing, prototype approach #1.** If it fails, escalate to #2 or #3.

Once input is resolved:
- Apply velocity in the movement direction at `MOVE_SPEED`
- Diagonal movement is normalized: `velocity = direction.normalize() * MOVE_SPEED`
- Play "Run" animation while moving, stop animation when idle

### Animations

| State | Animation |
|-------|-----------|
| Moving | `Run` (loop) |
| Idle | Stop movement animation (Hytale default idle) |

---

## 4. Formulas

### Movement Vector

```
cameraYaw = 45 degrees (fixed)

// Raw input from WASD (each axis is -1, 0, or 1)
inputX = (D pressed ? 1 : 0) - (A pressed ? 1 : 0)
inputZ = (W pressed ? 1 : 0) - (S pressed ? 1 : 0)

// Rotate by camera yaw
moveX = inputX * cos(cameraYaw) - inputZ * sin(cameraYaw)
moveZ = inputX * sin(cameraYaw) + inputZ * cos(cameraYaw)

// Normalize to prevent fast diagonals
direction = normalize(moveX, moveZ)
velocity = direction * MOVE_SPEED
```

Where `MOVE_SPEED = 4.0` (matches existing code constant).

### Facing Direction

```
facingYaw = atan2(-moveX, moveZ)
```

---

## 5. Edge Cases

- **Opposing keys held (W+S or A+D)**: Cancel out, net input is 0, player stops
- **Player against a wall**: Hytale's collision handles this — velocity is applied,
  engine resolves collision
- **Player disconnects while moving**: Movement state is per-player, cleaned up on
  disconnect event
- **Zero input vector**: Skip normalize (avoid divide by zero), apply zero velocity

---

## 6. Dependencies

- **Depended on by**: Combat System (needs player position/facing for attacks),
  Run State Machine (needs to know if player is alive to allow movement),
  Camera System (follows player position), Health & Lives, Enemy AI
- **Depends on**: Nothing — foundation system
- **Hytale API dependency**: Player input events (keyboard), `ChangeVelocity` packet,
  `AnimationUtils` for run/idle animations, `PlayerRef` for player entity access
- **Replaces**: `ClickToMoveHandler` — delete the class and its registration in
  `Main.java` (already commented out). Do not keep as dead code.
- **Integration note**: Movement should be disable-able by the Run State Machine
  (e.g., during floor transitions, upgrade selection, game over). Expose a
  `setMovementEnabled(PlayerRef, boolean)` method.

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MOVE_SPEED` | 4.0 | 2.0 - 8.0 | Player movement speed. Too fast = hard to control. Too slow = feels sluggish. |
| `CAMERA_YAW` | 45deg | Fixed for MVP | The angle used to rotate WASD input. Must match Camera System yaw. |

---

## 8. Acceptance Criteria

- [ ] WASD moves the player in camera-relative directions
- [ ] Diagonal movement (W+D, etc.) is not faster than cardinal movement
- [ ] Player model faces the direction of movement
- [ ] Player stops immediately when keys are released (no momentum)
- [ ] Run animation plays while moving, stops when idle
- [ ] Opposing keys cancel out (W+S = no movement)
- [ ] `ClickToMoveHandler` is removed or disabled — no click-to-move in MVP
- [ ] Movement speed matches `MOVE_SPEED` tuning knob
- [ ] Player movement works correctly at the fixed 45deg camera angle
