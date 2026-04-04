# System Design: Floor Generation

> **Status**: Approved
> **Created**: 2026-04-03
> **Systems Index**: design/gdd/systems-index.md (#9)

---

## 1. Overview

Floor Generation creates each dungeon floor by assembling pre-built room templates
into a connected layout. When the Run State Machine enters DESCENDING, Floor
Generation picks a set of rooms from a template library, arranges them on a grid,
connects them with corridors, and places the spawn point, exit zone, and mob spawn
locations. Each floor feels different because the room selection, arrangement, and
mob placement are randomized — but every room is hand-crafted to ensure visual
quality and gameplay flow. The system outputs a ready-to-play floor with a mob
count that it reports to the Run State Machine.

---

## 2. Player Fantasy

Every floor feels like uncharted territory. You step through the entrance and
don't know what's ahead — is it a tight corridor ambush or an open arena swarm?
The dungeon never gets familiar. This is the engine behind "One More Floor" —
curiosity about what's next. The player never thinks about room templates or grid
layouts; they just experience a dungeon that's different every time they play.

---

## 3. Detailed Rules

### Floor Layout

- Floors use a **linear chain** layout: Spawn Room → Combat Room(s) → Exit Room
- Each floor has `ROOMS_PER_FLOOR` rooms (3-5, configurable via tuning knob)
- Room order: 1 spawn room + (N-2) combat rooms + 1 exit room
- Rooms are placed sequentially along a single axis, connected by fixed-length corridors

### Room Templates

Each room template is defined by two files:
1. **Schematic file**: Pre-built block structure (Hytale-compatible format)
2. **JSON metadata**: Properties for the generation system

```json
{
  "id": "combat_arena_01",
  "type": "combat",
  "size": { "x": 20, "z": 20, "y": 8 },
  "doors": {
    "entry": { "x": 10, "y": 0, "z": 0 },
    "exit": { "x": 10, "y": 0, "z": 19 }
  },
  "spawnPoint": { "x": 10, "y": 0, "z": 2 },
  "exitZone": { "x": 8, "y": 0, "z": 17, "width": 4, "depth": 2 },
  "mobSpawns": [
    { "x": 5, "y": 0, "z": 10 },
    { "x": 15, "y": 0, "z": 10 },
    { "x": 10, "y": 0, "z": 15 }
  ],
  "minFloor": 1,
  "maxFloor": 99,
  "tags": ["arena", "open"]
}
```

- `spawnPoint`: Required for spawn rooms — player teleport destination. Optional
  for other room types.
- `exitZone`: Required for exit rooms — collision trigger area that signals floor
  completion. Defined as a rectangular area (x, z, width, depth at given y).

### Room Types

| Type | Purpose | Count per Floor | Special Properties |
|------|---------|-----------------|-------------------|
| **spawn** | Player starts here | Always 1 (first) | No mobs, has spawn point marker |
| **combat** | Mobs to fight | 1-3 (middle rooms) | Has `mobSpawns` list |
| **exit** | Floor completion zone | Always 1 (last) | Has exit trigger zone, may have mobs |

### Room Template Library

MVP target: **10-15 templates** total:
- 2-3 spawn room variants
- 6-8 combat room variants (small arena, large arena, corridor ambush, etc.)
- 2-3 exit room variants

### Corridors

- Fixed-length straight corridors connect each room's exit door to the next room's
  entry door
- Corridor dimensions: `CORRIDOR_LENGTH` blocks long, 3 blocks wide, 4 blocks tall
- Corridors are generated programmatically (no templates needed) — walls, floor, ceiling
- No mobs spawn in corridors

### Generation Process

When Run State Machine transitions to DESCENDING, it calls
`FloorGenerator.generateFloor(playerId, floorNumber, callback)`. Floor Gen then:

1. **Clean up previous floor**: Clear old blocks to air (if any)
2. **Select room count**: Random between `MIN_ROOMS` and `MAX_ROOMS`
3. **Pick spawn room**: Random from spawn templates
4. **Pick combat rooms**: Random from combat templates eligible for `currentFloor`
   (respecting `minFloor`/`maxFloor`)
5. **Pick exit room**: Random from exit templates
6. **Calculate positions**: Place rooms sequentially along the Z axis with corridor gaps
7. **Place blocks**: Write room schematics and corridor blocks into the world
8. **Register mob spawns**: Collect all `mobSpawns` positions from placed combat/exit rooms
9. **Spawn mobs**: Create enemies at spawn positions (mob type/count owned by
   Difficulty Scaling)
10. **Set mob count**: Call `RunStateManager.setMobCount(playerId, totalMobs)`
11. **Teleport player**: Move player to the spawn room's `spawnPoint`
12. **Signal ready**: Invoke the callback — Run State Machine transitions to FLOOR_ACTIVE

The Run State Machine does **not** use a timer for the DESCENDING→FLOOR_ACTIVE
transition. It waits for Floor Gen's callback. The `DESCENDING_TRANSITION_DURATION_MS`
is a minimum visual delay before the callback is invoked (Floor Gen holds the callback
if generation completes faster than the minimum duration).

### Mob Respawn on Death

When the player dies and respawns on the same floor, Floor Gen owns mob respawn:

1. Run State Machine calls `FloorGenerator.resetMobs(playerId)`
2. Floor Gen despawns any surviving mobs from the current floor
3. Floor Gen re-spawns mobs at the original spawn positions (same types/counts)
4. Floor Gen calls `RunStateManager.setMobCount(playerId, totalMobs)`

The floor layout (blocks, rooms, corridors) is **not** regenerated — only mobs reset.

### Floor Generation API

```java
// Generate a new floor — async, invokes callback when ready
void generateFloor(UUID playerId, int floorNumber, Runnable onReady)

// Clean up all floor blocks and mobs for a player
void cleanupFloor(UUID playerId)

// Reset mobs to original spawn positions (same floor layout)
void resetMobs(UUID playerId)

// Remove all data for a disconnected player
void removePlayer(UUID playerId)
```

### Floor Placement in World

- Only one floor exists at a time per player (MVP)
- Previous floor blocks are cleaned up (cleared to air) before the new floor is placed
- All floors use the same Y position: `FLOOR_Y = 100`
- Each player's floor is offset on the X axis: `playerFloorX = playerIndex * FLOOR_X_SPACING`
- This avoids overlap between players without wasting vertical space

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Run State Machine** | Requests floor generation during DESCENDING; receives `setMobCount()` when floor is ready |
| **Difficulty Scaling** | Provides mob types and counts for the current floor (Floor Gen places them) |
| **Enemy AI** | Spawned mobs are handed to Enemy AI for behavior management |
| **Player Data** | Floor Gen does not interact directly — upgrades are handled by Floor Upgrade Selection |

---

## 4. Formulas

### Room Count per Floor

```
roomCount = random(MIN_ROOMS, MAX_ROOMS)
```

Where:
- `MIN_ROOMS = 3`
- `MAX_ROOMS = 5`

Combat rooms = `roomCount - 2` (minus 1 spawn + 1 exit). Range: 1-3 combat rooms.

### Floor World Position

```
floorY = FLOOR_Y  (constant, always 100)
floorX = playerIndex * FLOOR_X_SPACING
```

Where:
- `FLOOR_Y = 100` — Y coordinate for all floors (old floor is cleaned up first)
- `FLOOR_X_SPACING = 200` — horizontal gap between players' floors

### Room Placement Along Z Axis

```
roomStartZ = previousRoomEndZ + CORRIDOR_LENGTH
roomEndZ = roomStartZ + roomTemplate.size.z
```

Where:
- `CORRIDOR_LENGTH = 8` — blocks between rooms
- First room starts at Z = 0

### Total Floor Length

```
totalZ = sum(roomSizes.z) + (roomCount - 1) * CORRIDOR_LENGTH
```

Example: 4 rooms of size 20 + 3 corridors of 8 = 80 + 24 = 104 blocks long.

### Mob Spawn Count

Mob count is **not** calculated by Floor Generation — it's the sum of mobs actually
spawned by Difficulty Scaling at the mob spawn positions provided by room templates.
Floor Gen reports this count to RunStateManager after spawning.

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
  - Enemy AI — mobs are spawned at positions defined by room templates
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
