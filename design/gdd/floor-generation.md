# System Design: Floor Generation

> **Status**: Approved (revised 2026-04-05 — single-room-per-floor model)
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

- **Room JSON file missing**: Fail loudly at run start — log error, stop
  generation, send chat message to player ("Floor N config missing").
  Content bug, not a gameplay case.

- **Room JSON has invalid data** (e.g., `exitZone` missing, negative width,
  spawn group with 0 mobs): Log error listing the problem, stop generation.
  Validation runs once at `generateFloor()` time.

- **`playerSpawnPoint` inside a solid block** (author error): Player is
  teleported there; Hytale handles collision. **Acceptance**: designer
  verifies spawn points are clear.

- **Exit zone never arms (mobsRemaining > 0 forever)**: Caused by Enemy AI
  pre-counting a mob that never spawns (blocked spawn position). Floor is
  unwinnable. Detection: if FLOOR_ACTIVE exceeds 5 minutes, log a warning.
  Mitigation: designer ensures spawn points are clear.

- **Player enters exit zone before all mobs dead** (exit disarmed): Nothing
  happens. Zone only fires after arming.

- **Player enters exit zone during DESCENDING**: Not possible — position
  checks are paused outside FLOOR_ACTIVE.

- **Exit zone entered by something other than player**: Ignored. Zone
  polling checks player position only.

- **Player dies while standing on exit zone**: Death transitions to DEAD;
  respawn teleports player back to `playerSpawnPoint`, disarming the exit
  zone. No double-trigger.

- **Player dies in exit zone on floor 10 after clearing**: `onFloorCleared`
  is **sticky** — once fired, the exit zone remains armed across respawns
  on that floor. Victory flow re-triggers on next zone entry.

- **Floor 10 cleared but player leaves before advancing**: On reconnect,
  `currentFloor` persists (Player Data). Floor 10 loads as fresh (mobs
  re-spawn via respawn flow). No mid-floor save state in MVP.

- **Player disconnects during floor load**: `removePlayer()` is called.
  Cancel pending work, clear loaded room data, call
  `enemyManager.removePlayer()`. No blocks to clean (designer-built world).

- **Player falls off map**: Not Floor Gen's responsibility. Player
  Controller or Health & Lives handles death from falling.

- **Two players on the same server**: Both share the same 10 rooms (same
  physical coordinates). Each player has their own mob set (Enemy AI tags
  by playerId). Exit zone checks run per-player. **Known issue**: both
  players see each other's enemies; mob damage is cross-player. **Accept
  for MVP** — multiplayer is deferred.

---

## 6. Dependencies

### Depends On

- **Run State Machine** — Triggers `generateFloor()` during DESCENDING;
  calls `onFloorCleared()` when `mobsRemaining == 0` so Floor Gen arms
  the exit zone; receives `requestFloorAdvance()` / `requestRunVictory()`.
- **Enemy AI** — Floor Gen passes room `spawnGroups` to
  `enemyManager.registerFloor()` which returns total mob count; delegates
  `resetMobs()` to Enemy AI; calls `removePlayer()` on disconnect.
- **Player Controller** — Provides player position for exit-zone polling
  and `playerRef` for teleportation.

### Depended On By

- **Enemy AI** — Receives `spawnGroups` from Floor Gen; depends on Floor Gen
  calling `registerFloor()` and `resetMobs()` at correct lifecycle moments.
- **Run State Machine** — Waits for Floor Gen's callback before transitioning
  to FLOOR_ACTIVE; receives floor-advance / victory requests.

### Hytale API Dependencies

- **Teleportation** — `Player.moveTo()` for spawn-point teleport
- **Chunk loading** — Ensure the room's area is loaded before teleporting
  the player (if Hytale requires explicit loading)
- **Entity spawning** (delegated to Enemy AI, not Floor Gen directly)

### Asset Dependencies

- **Room metadata JSON** — `config/rooms/floor_01.json` through
  `floor_10.json`. Designer-authored.
- **Room block geometry** — Pre-built by designer in the world save file.
  Not an asset Floor Gen loads; it exists in the persistent world.

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MAX_FLOOR` | 10 | 5 - 50 | Total floors in a run. Must match number of room JSON files in `config/rooms/`. |
| `FLOOR_CLEAR_TIMEOUT` | 300s (5 min) | 60 - 600s | Warning threshold for an unwinnable floor (mobsRemaining stuck > 0). |

Note: Mob types, counts, and difficulty per floor are owned by Difficulty
Scaling and Enemy AI, not Floor Gen. Room geometry is content, not a code
knob. Per-room spawn groups, spawn points, and exit zones are authored in
`config/rooms/floor_XX.json`.

### Removed from prior revision

`MIN_ROOMS`, `MAX_ROOMS`, `CORRIDOR_LENGTH`, `FLOOR_Y`, `FLOOR_X_SPACING` —
all obsoleted by the single-room-per-floor, designer-positioned model.

---

## 8. Acceptance Criteria

### Floor Lifecycle

- [ ] `generateFloor(playerId, N)` loads `config/rooms/floor_{N:02d}.json`
  and teleports the player to `playerSpawnPoint`
- [ ] `spawnGroups` from the room JSON are passed to
  `enemyManager.registerFloor()`; returned mob count is forwarded to
  `runStateManager.setMobCount()`
- [ ] Exit zone starts **disarmed** on floor start
- [ ] Exit zone arms when `onFloorCleared()` is called (mobsRemaining == 0)
- [ ] Exit portal is spawned at the exit-zone center when zone arms

### Floor Advancement

- [ ] Entering the armed exit zone on floors 1-9 calls
  `requestFloorAdvance()` → loads next floor
- [ ] Entering the armed exit zone on floor 10 calls
  `requestRunVictory()` instead
- [ ] Exit zone is disarmed after the advance teleport

### Player Respawn

- [ ] `resetMobs(playerId)` delegates to `enemyManager.resetMobs()`
- [ ] `setMobCount()` is re-called with the original pre-counted total
- [ ] Player is teleported back to `playerSpawnPoint`
- [ ] Exit zone is disarmed; portal despawned

### Run Reset

- [ ] New run from GAME_OVER loads `floor_01.json` and resets all state
- [ ] Enemy AI state is cleared via `removePlayer()` → fresh `registerFloor()`

### Error Handling

- [ ] Missing room JSON file → error logged, generation halted, player notified
- [ ] Invalid JSON data (missing exitZone, bad spawn group) → validation
  error logged at `generateFloor()` time
- [ ] Unwinnable floor (5-minute timeout) → warning logged

### Multiplayer (Known Limitations)

- [ ] Two players sharing the same rooms: each has their own mob set and
  exit-zone poll. Cross-player mob visibility accepted for MVP.
