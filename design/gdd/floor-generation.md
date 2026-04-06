# System Design: Floor Generation

> **Status**: In Revision (2026-04-05 — single-room-per-floor scope change)
> **Created**: 2026-04-03
> **Systems Index**: design/gdd/systems-index.md (#9)

---

## 1. Overview

Floor Generation drives the player through 10 hand-built dungeon rooms, one
per floor. Rooms are pre-built by the designer directly in the world save —
Floor Gen places no blocks at runtime. It owns per-room metadata (spawn
point, spawn groups, exit zone) and the floor lifecycle: teleport the player
in, hand spawn groups to Enemy AI, detect exit-zone entry, advance. Floor N
always loads room N; clearing floor 10 ends the run in victory.

---

## 2. Player Fantasy

Each floor is a known arena: you've fought here before. The terrain is
memorable — this is the columned hall, that's the pit room, this is the
narrow bridge — and learning its geometry is part of getting better. What
changes between runs is the threat: which enemy archetypes spawn, which
waves fire, how your current gear copes. This serves **One More Floor** by
giving the player checkpoints they recognize — "just get me past the pit
room" — and **Simple Choices, Big Impact** by letting gear decisions play
out on familiar ground. The fantasy is mastery through repetition: the
dungeon doesn't change, **you do**.

---

## 3. Detailed Rules

### Room Metadata Schema

Each floor has one JSON metadata file: `config/rooms/floor_01.json`,
`floor_02.json`, … `floor_10.json`. The room's blocks are pre-built by the
designer in the world save; the JSON carries only the gameplay data.

```json
{
  "floorNumber": 1,
  "name": "Columned Hall",
  "worldOrigin": { "x": 0, "y": 100, "z": 0 },
  "playerSpawnPoint": { "x": 5, "y": 101, "z": 5 },
  "spawnGroups": [
    {
      "id": "floor_01_wave_1",
      "trigger": { "type": "zone", "x": 8, "z": 10, "width": 4, "depth": 2 },
      "mobs": [
        { "x": 3, "y": 101, "z": 15 },
        { "x": 12, "y": 101, "z": 15 }
      ],
      "nextGroup": "floor_01_wave_2"
    },
    {
      "id": "floor_01_wave_2",
      "trigger": { "type": "on_cleared" },
      "mobs": [
        { "x": 7, "y": 101, "z": 20 }
      ],
      "nextGroup": null
    }
  ],
  "exitZone": { "x": 6, "y": 100, "z": 25, "width": 4, "depth": 2 }
}
```

**Field notes:**

- `worldOrigin`: informational bookmark to the room's reference corner. Not
  used at runtime; designer reference only.
- `playerSpawnPoint`: world-absolute coordinates where the player is
  teleported on floor start.
- `spawnGroups`: identical schema to the Enemy AI GDD. Floor Gen passes this
  list straight through to `enemyManager.registerFloor()`.
- `exitZone`: axis-aligned zone (world-absolute) that, when armed, teleports
  the player to the next floor.

### Coordinate System

All coordinates in room JSON are **world-absolute**. `worldOrigin` exists
only as a designer bookmark — no runtime offsetting is applied. This keeps
Floor Gen simple and makes the JSON directly inspectable.

### Room Placement in World

- The designer hand-builds all 10 rooms in the world save before playtest.
- Rooms may be placed **anywhere** in the world — no fixed layout, axis, or
  spacing enforced.
- Floors reuse the same physical rooms across runs — nothing is placed or
  cleared at runtime.
- Single-player MVP: no per-player offset. Multiplayer concerns deferred.

### Floor Lifecycle

**Start of run (floor 1):**

1. Run State Machine enters DESCENDING
2. Floor Gen loads `config/rooms/floor_01.json`
3. Floor Gen calls
   `enemyManager.registerFloor(playerId, world, roomData.spawnGroups)` —
   Enemy AI arms zone triggers, returns total mob count
4. Floor Gen calls `runStateManager.setMobCount(playerId, totalMobs)`
5. Floor Gen teleports player to `roomData.playerSpawnPoint`
6. Floor Gen starts **exit-zone monitoring** for this floor (initially
   **disarmed**)
7. Signal ready → Run State Machine → FLOOR_ACTIVE

**During floor (FLOOR_ACTIVE):**

- Enemy AI handles mob spawning via triggers/waves
- Floor Gen polls player position each tick; checks exit zone only when
  **armed**
- When Run State Machine signals "floor cleared" (mobsRemaining == 0),
  Floor Gen **arms** the exit zone and spawns a visual exit portal at its
  center

**Exit-zone entry (armed):**

1. Player's (x, z) enters the armed exit zone
2. Floor Gen calls `runStateManager.requestFloorAdvance(playerId)` — Run
   State Machine transitions to DESCENDING
3. Run State Machine calls
   `FloorGenerator.generateFloor(playerId, floorNumber + 1, callback)`
4. Floor Gen loads next room's JSON, calls `enemyManager.registerFloor()`,
   teleports player, etc.

**Advance past floor 10:**

1. Clearing floor 10 arms its exit zone as usual
2. Entering the exit zone calls `runStateManager.requestRunVictory(playerId)`
   instead of `requestFloorAdvance`
3. Run State Machine transitions to VICTORY state (owned by RSM — to be added)
4. Floor Gen unloads the current room and calls
   `enemyManager.removePlayer(playerId)`

### Exit Portal

- Visual marker spawned at the exit-zone center when the floor clears.
  Implementation TBD — options: particle effect, block column, entity
  marker. Chosen at implementation time based on Hytale API.
- Portal visibility matches exit-zone armed state. Despawned on teleport out.
- Portal is **cosmetic** — the authoritative trigger is the exit-zone poll.
  If portal rendering fails, the zone still works.

### Exit-Zone Arming

- Armed: only when Run State Machine's `mobsRemaining == 0` for this floor
  (Run State Machine notifies Floor Gen via `onFloorCleared`)
- Disarmed: on floor start, on player respawn, and after the advance
  teleport

### Player Respawn on Same Floor

When player dies and respawns:

1. Run State Machine DEAD → FLOOR_ACTIVE transition
2. Floor Gen calls `enemyManager.resetMobs(playerId)` — despawns survivors,
   re-arms zone triggers
3. Floor Gen calls `runStateManager.setMobCount(playerId, totalMobs)` with
   the pre-counted total
4. Floor Gen teleports player back to `playerSpawnPoint`
5. Exit-zone state resets to **disarmed**; portal despawned if present

Room geometry is unchanged (blocks are part of the world, never touched).

### Run Reset (GAME_OVER → new run)

- Floor Gen resets `currentFloor = 1` for the player
- Start-of-run flow runs again, loading `floor_01.json`
- Exit-zone state cleared; any active portal despawned
- Enemy AI state cleared via `enemyManager.removePlayer()`

### Floor Generation API

```java
// Load the floor and position player. Called by Run State Machine.
void generateFloor(UUID playerId, int floorNumber, Runnable onReady)

// Called by Run State Machine when mobsRemaining hits 0 — arms the exit zone.
void onFloorCleared(UUID playerId)

// Delegates to Enemy AI — Floor Gen does not itself reset mobs.
void resetMobs(UUID playerId)

// Clean up Floor Gen state for a disconnected player.
void removePlayer(UUID playerId)
```

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Run State Machine** | Requests floor generation on DESCENDING; calls `onFloorCleared` so Floor Gen arms the exit zone; receives `requestFloorAdvance()` / `requestRunVictory()` on exit-zone entry |
| **Enemy AI** | Floor Gen passes `spawnGroups` via `registerFloor()`; delegates `resetMobs()`; calls `removePlayer()` on disconnect |
| **Player Controller** | Player position is polled for exit-zone check; `playerRef` used for teleport |
| **Player Data** | Floor Gen does not interact directly — upgrades handled by Floor Upgrade Selection |

---

## 4. Formulas

Floor Generation is a routing/lifecycle system — it has no gameplay math.
The only "formulas" are:

### Floor Advance

```
nextFloor = currentFloor + 1
if nextFloor > MAX_FLOOR → run victory
else → load config/rooms/floor_{nextFloor:02d}.json
```

Where:

- `currentFloor ∈ [1, 10]`
- `MAX_FLOOR = 10` (MVP)

### Exit-Zone Containment Check

Standard axis-aligned bounding-box test each tick:

```
playerInZone = (
    player.x >= zone.x AND player.x < zone.x + zone.width AND
    player.z >= zone.z AND player.z < zone.z + zone.depth
)
```

No Y check — the zone is a vertical column (player enters at any Y).

### Total Mob Count

```
totalMobs = sum(group.mobs.length for group in roomData.spawnGroups)
```

Computed once at floor-start by Enemy AI during `registerFloor()` and
returned to Floor Gen.

### Example: Floor 1 mob count

`floor_01.json` has:

- wave_1: 2 mobs
- wave_2: 1 mob

`totalMobs = 2 + 1 = 3`. Pre-counted; all 3 must die before the exit zone arms.

### Removed in 2026-04-05 revision

Room-count, floor world position (X spacing), Z-axis room placement, and
total floor length — all obsoleted by the single-room-per-floor model.

---

## 5. Edge Cases

- **Not enough templates for the floor**: If fewer eligible templates exist than
  combat rooms needed, reuse templates (allow duplicates within a floor). Log a warning.
- **No eligible combat templates for `currentFloor`**: Fall back to any combat
  template ignoring `minFloor`/`maxFloor` filters. Log a warning.
- **Floor generation takes longer than expected**: The Run State Machine waits for
  Floor Gen's callback — the transition doesn't auto-expire. If gen takes >5s, log
  a warning (performance issue). The `DESCENDING_TRANSITION_DURATION_MS` is a minimum
  visual delay, not a hard timeout.
- **Player dies during DESCENDING (while floor is generating)**: Not possible — combat
  is disabled during DESCENDING state.
- **Player respawns on same floor after death**: Floor is NOT regenerated. The existing
  layout persists. Run State Machine calls `FloorGenerator.resetMobs(playerId)` which
  despawns surviving mobs and re-spawns them at original positions. Floor Gen owns
  this flow and re-sets `mobsRemaining` via `RunStateManager.setMobCount()`.
- **New Run from GAME_OVER**: Previous floor blocks are cleared, floor 1 is
  regenerated from scratch with fresh random selection.
- **Player disconnects during floor generation**: Generation is cancelled, all
  placed blocks are cleaned up, run data is discarded.
- **Room schematic file missing or corrupt**: Skip that template, log an error.
  If all templates of a type are broken, fail with an error message to the player.
- **Two players on the same server**: Each player's floor is generated at a different
  X offset (`playerIndex * FLOOR_X_SPACING`) to prevent overlap. All players use
  the same Y level (`FLOOR_Y`).
- **Block placement fails (chunk not loaded)**: Ensure the target area is loaded
  before placing blocks. Use Hytale's chunk loading API if available.

---

## 6. Dependencies

- **Depends on**:
  - Run State Machine — triggers generation during DESCENDING, receives mob count
  - Difficulty Scaling (undesigned) — provides mob types and counts per spawn point.
    **Provisional**: until Difficulty Scaling is designed, Floor Gen uses a placeholder
    of 3 basic mobs per spawn point.
- **Depended on by**:
  - Enemy AI — consumes flattened `spawnGroups` from room templates via
    `registerFloor()`; owns all mob spawning, trigger arming, and mob lifecycle.
    Floor Gen delegates `resetMobs` to Enemy AI.
  - Difficulty Scaling — reads room template data (mob spawn count/positions) to decide
    what to spawn
  - Run State Machine — waits for Floor Gen to signal floor-ready before transitioning
    to FLOOR_ACTIVE
- **Hytale API dependencies**:
  - Block placement API (set blocks in world programmatically)
  - Schematic/structure loading (if available — fallback: manual block-by-block placement)
  - Entity spawning (create mob entities at positions)
  - Chunk loading (ensure target area is loaded)
  - Teleportation (move player to spawn point)
- **Asset dependencies**:
  - Room schematic files in `assets/rooms/` directory
  - Room metadata JSON in `config/rooms/` directory

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MIN_ROOMS` | 3 | 2 - 5 | Minimum rooms per floor. Too low = trivial floors. |
| `MAX_ROOMS` | 5 | 3 - 8 | Maximum rooms per floor. Too high = long clear time, slow pacing. |
| `CORRIDOR_LENGTH` | 8 | 4 - 16 | Blocks between rooms. Shorter = faster pace. Longer = breathing room. |
| `FLOOR_Y` | 100 | 50 - 200 | World Y for all floors. Must be within valid world height range. |
| `FLOOR_X_SPACING` | 200 | 150 - 500 | Horizontal gap between players' floors (multiplayer). |

Note: Mob types, counts, and difficulty per floor are owned by Difficulty Scaling,
not duplicated here. Room template variety is content, not a code knob.

---

## 8. Acceptance Criteria

- [ ] Floor generation produces a playable floor with spawn point, combat rooms, and exit zone
- [ ] Room count is between `MIN_ROOMS` and `MAX_ROOMS` (inclusive)
- [ ] First room is always a spawn type, last room is always an exit type
- [ ] Rooms are connected by corridors — player can walk from spawn to exit without gaps
- [ ] Mob spawn positions from room templates are populated with enemies
- [ ] `RunStateManager.setMobCount()` is called with the correct total mob count
- [ ] Player is teleported to spawn room's spawn point after generation
- [ ] Old floor blocks are cleaned up before new floor is placed (same Y position reused)
- [ ] Previous floor blocks are cleaned up when a new floor is generated
- [ ] Room templates with `minFloor`/`maxFloor` filters are respected
- [ ] Template reuse is allowed when not enough unique templates are available
- [ ] Missing or corrupt schematic files are handled gracefully (skip + log error)
- [ ] Player respawn on same floor reuses existing layout, resets mobs
- [ ] New Run generates a fresh floor 1 with new random template selection
- [ ] Multiple players get floors at separate X offsets
