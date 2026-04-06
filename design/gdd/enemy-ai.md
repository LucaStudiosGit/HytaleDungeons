# System Design: Enemy AI

> **Status**: Designed
> **Created**: 2026-04-05
> **Systems Index**: design/gdd/systems-index.md (#10)
> **Implements Pillar**: Instant Action, One More Floor

---

## 1. Overview

Enemy AI wires Hytale's native creature behaviors into the dungeon's combat loop.
Rather than authoring custom targeting, pathfinding, or attack logic, this system
selects from Hytale's existing mob roster — mapping three archetypes (melee,
ranged, rusher) onto existing Hytale creatures — and spawns them at the positions
Floor Generation provides. Movement, aggro, and attack cadence are governed
entirely by Hytale's built-in AI. Enemy AI owns the integration edges: picking
which Hytale mobs fulfill each archetype, spawning them with correct stats,
tracking custom HP (since Combat cancels Hytale's native damage pipeline), routing
enemy attacks through the Combat System, and firing `onMobKilled` when an enemy
dies. In short: Hytale provides the brains; this system provides the bookkeeping.

---

## 2. Player Fantasy

Enemies are obstacles that fall satisfyingly fast. Most mobs die in 1-3 hits,
letting the player mow through rooms and feel powerful with every swing. The
fantasy is crowd-clearing: you are the dangerous one, the dungeon is the
quantity problem. Archetypes shape the texture of that feeling — **melee** mobs
are the bulk you chop through and the reason your weapon matters; **ranged**
mobs punish standing still and push the player to keep moving; **rushers**
break the player's rhythm and force repositioning. No single enemy is a puzzle,
but a room full of them is. This serves **Instant Action** (combat is always
happening, rarely pausing) and **One More Floor** (escalating swarms make each
floor feel like a fresh wave of enemies to crush).

---

## 3. Detailed Rules

### Archetypes

Three archetypes, each mapped to a specific Hytale creature. The exact Hytale
creature IDs are **TBD** — chosen during implementation by testing Hytale's
mob roster.

| Archetype | Role | Hytale Creature (TBD) | Base HP | Base Attack | Move Speed |
|-----------|------|------------------------|---------|-------------|------------|
| **Melee** | Bulk crowd | TBD (e.g., zombie-like) | `MELEE_BASE_HP` | `MELEE_BASE_ATK` | Normal |
| **Ranged** | Stand-still punisher | TBD (e.g., archer/skeleton) | `RANGED_BASE_HP` | `RANGED_BASE_ATK` | Normal |
| **Rusher** | Repositioning threat | TBD (e.g., wolf/fast creature) | `RUSHER_BASE_HP` | `RUSHER_BASE_ATK` | Fast |

Native Hytale AI drives movement, targeting, and attack timing per creature.

### Archetype Assignment Per Spawn

When a spawn group fires, each mob position inside the group rolls for an
archetype **independently**, using **floor-level weights**
(default: 60% melee / 30% ranged / 10% rusher):

1. Per-position roll at spawn time — no shared roll across the group
2. Weights are floor-level (all groups on a given floor use the same weights)
3. Weights are tuning knobs, eventually overridable by Difficulty Scaling
4. **Provisional**: until Difficulty Scaling is designed, weights are static
   constants

### Spawn Groups (Not Raw Positions)

Room templates define **spawn groups**, not bare mob positions. Each group:

```json
{
  "id": "combat_arena_01_wave_1",
  "trigger": { "type": "zone", "x": 10, "z": 5, "width": 4, "depth": 2 },
  "mobs": [
    { "x": 5, "z": 10 },
    { "x": 15, "z": 10 },
    { "x": 10, "z": 15 }
  ],
  "nextGroup": "combat_arena_01_wave_2"
}
```

**Trigger types:**

| Type | Behavior |
|------|----------|
| `zone` | Spawns when player enters the axis-aligned zone. One-shot — zone disarms after firing. |
| `on_cleared` | Spawns when another group's mobs are all dead. Referenced by `nextGroup` from the prior group. |

**Wave chains**: Group A (`zone` trigger) spawns → when all of A's mobs die,
`nextGroup` fires → Group B spawns → etc. Chains terminate when `nextGroup`
is null.

### Spawning Flow

Floor Gen calls Enemy AI with the full list of spawn groups for the floor
(flattened across all placed room templates, positions already world-space
adjusted):

```java
int registerFloor(UUID playerId, World world, List<SpawnGroup> groups)
// Returns total mob count across all groups
```

Enemy AI:

1. Stores all groups in `PlayerFloorState`
2. **Arms zone triggers** — registers position listeners for groups with
   `trigger.type == zone`
3. Does **not** spawn any mobs yet
4. Returns total mob count (sum across all groups) — Run State Machine uses
   this as the floor's target

### Zone Trigger Activation

Each server tick, Enemy AI polls the player's (x, z) position against armed
zones. (If Hytale later exposes a player-move event, this check can be
converted to event-driven.)

1. If player's (x, z) enters an armed zone → attempt to activate the group
2. **Soft-cap check**: if spawning the group would push total live mobs over
   `MAX_LIVE_MOBS`, skip activation this tick — leave the zone **armed** and
   retry next tick. Log a warning.
3. Otherwise: disarm the zone (one-shot)
4. Spawn all mobs in the group at their positions, register each in EnemyManager

### Wave Chain Activation (`on_cleared`)

When a mob dies (`killMob`):

1. Decrement its group's live count
2. If group's live count reaches 0 **and** the group has a `nextGroup`:
   - Look up the referenced group and spawn it immediately
3. Call `runStateManager.onMobKilled(playerId)` as before

### Mob Count Reporting

Total mob count = sum of `mobs.length` across **all** spawn groups for the
floor (counted whether spawned yet or not — they will all be killable before
the floor clears). Floor Gen reports this to Run State Machine via
`setMobCount`. Run State Machine's "floor cleared" signal fires when
`mobsRemaining == 0`.

### Enemy Spawning (Internal)

For each position in a firing group:

1. Pick archetype from weighted distribution
2. Spawn Hytale creature at position
3. Record `entityId → EnemyState { archetype, currentHP, maxHP, playerId, groupId }`
   in `EnemyManager`

### HP Tracking

- `EnemyManager` owns a `ConcurrentHashMap<UUID entityId, EnemyState>`
- `EnemyState { archetype, currentHP, maxHP, playerId, groupId }`
- Hytale's native entity HP is **not used** — Combat cancels native damage events
- Enemy is looked up by entity UUID when damage or death is processed

### Damage Flow (Enemy Takes Damage)

Combat System is the caller. Enemy AI exposes:

```java
void applyDamage(UUID entityId, int damage)
```

1. Look up `EnemyState` — if missing, ignore (already dead)
2. `currentHP = max(0, currentHP - damage)`
3. If `currentHP == 0`: call internal `killMob(entityId)`

### Damage Flow (Enemy Attacks Player)

Enemy AI does **not** compute enemy damage. Combat System intercepts the
`Damage` event from the Hytale mob, looks up the attacker's `EnemyState`,
reads `archetype.baseAttack`, and routes through
`HealthManager.takeDamage(playerId, rawDamage)`.

### Death Flow (`killMob`)

1. Despawn the Hytale entity from the world
2. Decrement the group's live count; fire `nextGroup` if it hits 0
3. Remove `EnemyState` from EnemyManager
4. Call `runStateManager.onMobKilled(playerId)` directly
5. Log the kill

### Cleanup Triggers

Enemy AI despawns all managed mobs and clears its maps in these cases:

| Trigger | Behavior |
|---------|----------|
| **Floor transition** (new floor) | Despawn all mobs for that player, clear spawn-group state |
| **Player death → `resetMobs()`** | Despawn survivors, re-register all groups with zones re-armed, clear entity state |
| **Player disconnect** | Despawn all mobs for that player, clear state |
| **Run over (GAME_OVER)** | Despawn via floor cleanup |

### `resetMobs` Contract

Floor Gen calls `enemyManager.resetMobs(playerId)`. Enemy AI:

1. Despawns all live entities for that player
2. Re-arms all `zone` triggers from the cached group list
3. Clears `on_cleared` group activation state
4. Re-rolls archetype assignments per position (fresh randomness on respawn)

### Active-State Rule

Enemy AI only processes damage and death during `FLOOR_ACTIVE`. During other
Run States:

- Existing Hytale entities continue to exist (cheaper than de-/re-spawning)
- `applyDamage()` calls are ignored (combat is disabled)
- Zone triggers are not checked
- Enemy attacks on player are cancelled by Combat's state check

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Floor Generation** | Provides full flattened list of spawn groups (with triggers); calls `registerFloor()` after block placement; calls `resetMobs()` on respawn |
| **Combat System** | Calls `applyDamage(entityId, damage)` when player hits a mob; reads `archetype.baseAttack` when enemy hits player |
| **Run State Machine** | Receives `onMobKilled(playerId)` on each death; owns mob count |
| **Health & Lives** | Indirect only — enemy attacks routed through Combat → HealthManager |
| **Difficulty Scaling** (future) | Will override archetype weights, HP, attack values per floor. Currently: static constants. |

---

## 4. Formulas

### Base Archetype Stats (Constants)

```
MELEE_BASE_HP   = 20    // 3 hits @ Iron Sword (8 dmg)
MELEE_BASE_ATK  = 10    //  5% of 200 MAX_HP

RANGED_BASE_HP  = 12    // 2 hits @ Iron Sword
RANGED_BASE_ATK = 14    //  7% of 200 MAX_HP

RUSHER_BASE_HP  = 10    // 2 hits @ Iron Sword — easiest to kill
RUSHER_BASE_ATK = 40    // 20% of 200 MAX_HP — glass cannon
```

Design intent: Melee = the work. Ranged = pressure to keep moving.
Rusher = dies fast but punishes being caught.

Variables and ranges:

- `*_BASE_HP`: integer, range `[5, 100]` — below 5 is trivially one-shot;
  above 100 breaks fodder fantasy
- `*_BASE_ATK`: integer, range `[1, 50]` — below 1 is harmless; above 50
  pierces most armor

### Per-Spawn Stat Variance

At spawn time, each mob's HP and ATK are rolled from a uniform distribution
around the archetype base:

```
actualHP  = round( baseHP  * uniformRandom(1 - HP_VARIANCE,  1 + HP_VARIANCE) )
actualATK = round( baseAtk * uniformRandom(1 - ATK_VARIANCE, 1 + ATK_VARIANCE) )
```

Default variance:

```
HP_VARIANCE  = 0.30   // ±30% → actualHP  ∈ [0.7x, 1.3x]
ATK_VARIANCE = 0.25   // ±25% → actualATK ∈ [0.75x, 1.25x]
```

### Hit-Count Targets

| Archetype | Base HP | HP range (±30%) | Kills @ 8 dmg/swing |
|-----------|---------|-----------------|---------------------|
| Melee     | 20      | [14, 26]        | 2-4 hits (mostly 3) |
| Ranged    | 12      | [8, 16]         | 1-2 hits            |
| Rusher    | 10      | [7, 13]         | 1-2 hits            |

### Archetype Weight Distribution

```
roll = random.nextFloat()  // [0.0, 1.0)
if      roll < WEIGHT_MELEE                           → Melee
else if roll < WEIGHT_MELEE + WEIGHT_RANGED           → Ranged
else                                                  → Rusher
```

Default weights:

```
WEIGHT_MELEE  = 0.60
WEIGHT_RANGED = 0.30
WEIGHT_RUSHER = 0.10
```

**Invariant**: `WEIGHT_MELEE + WEIGHT_RANGED + WEIGHT_RUSHER == 1.0`

### Level-Scaled HP and Attack (Hook for Difficulty Scaling)

Full spawn stat formula, including (provisional) floor scaling:

```
effectiveHP  = round( baseHP  * uniformRandom(1 - HP_VARIANCE,  1 + HP_VARIANCE)  * difficultyMultiplier(floor) )
effectiveATK = round( baseAtk * uniformRandom(1 - ATK_VARIANCE, 1 + ATK_VARIANCE) * difficultyMultiplier(floor) )
```

**Provisional**: until Difficulty Scaling is designed, `difficultyMultiplier(floor) = 1.0`
(flat base stats on every floor). Difficulty Scaling will override this
function.

### Enemy Damage Examples

**Rusher vs. no armor, median roll (baseAtk=40):**

```
actualDmg = max(1, 40 - 0) = 40    // 20% of 200 MAX_HP
```

5 rusher hits = dead player.

**Rusher vs. Iron Armor (dmgReduction=6), median roll:**

```
actualDmg = max(1, 40 - 6) = 34    // 17% of 200 MAX_HP
```

~6 rusher hits = dead player.

**Melee vs. Iron Armor, median roll:**

```
actualDmg = max(1, 10 - 6) = 4     // 2% of 200 MAX_HP
```

50 melee hits = dead player. True fodder.

### Floor-Clear Pacing Example

Typical MVP floor: 4 rooms × ~4 mobs average = 16 mobs. Default 60/30/10 weights:

- ~10 Melee × 3 hits = 30 swings
- ~5 Ranged  × 2 hits = 10 swings
- ~1 Rusher × 2 hits = 2 swings

Total ≈ 42 swings. At 400 ms sword cooldown, floor clear is ~17 seconds of
continuous hitting (plus movement, dodging, and repositioning).

---

## 5. Edge Cases

- **Player dies mid-wave (before `nextGroup` fires)**: Run State Machine
  transitions to DEAD, Floor Gen calls `resetMobs()`. Enemy AI despawns all
  live mobs (wave 1 survivors + any prior waves) and re-arms the original
  zone triggers. Player respawns; first zone must be re-entered to restart
  the wave chain.

- **Zone peek**: Zones are checked per tick / movement event. A zone trigger
  fires the first tick the player's (x, z) is inside. No hysteresis — the
  player cannot "peek" a zone without triggering it.

- **Two zones overlap**: Both fire on the same tick — both groups spawn.
  Intentional feature (level designers can stack pressure).

- **`nextGroup` references a missing group ID**: Log an error, treat as
  end-of-chain (no further wave). Do not crash. Mob count on the RSM is
  already pre-counted, so `mobsRemaining` cannot reach 0 — flag this as a
  content bug during validation.

- **`nextGroup` loops (A → B → A)**: Detected at `registerFloor()` by
  walking chains from every zone-triggered group. If a cycle is found, break
  the loop by nulling the offending `nextGroup` and log an error.

- **Player dies during DESCENDING or DEAD state**: Combat is disabled in
  those states — damage events are cancelled before reaching Health & Lives.
  Enemy AI is not involved.

- **Mob killed during non-FLOOR_ACTIVE state**: Should not happen (combat
  disabled). If a rogue `applyDamage` call arrives, process it — the death
  is legitimate, no extra state guard on Enemy AI.

- **Spawn position blocked by a block**: Hytale's spawn API either succeeds
  (mob nudged) or rejects. If rejection occurs, log and skip that mob.
  Because the floor's pre-counted mob total then cannot reach zero, the
  floor cannot complete. **Acceptance**: room templates must be authored so
  spawn points are always empty space.

- **Mob falls below the floor**: If a mob's Y drops below `FLOOR_Y - 5`,
  treat as killed: despawn, decrement group counter, call `onMobKilled`.
  Prevents softlock from runaway entities.

- **Ranged mob cannot path to player (blocked)**: Not our concern —
  Hytale's native AI handles pathing. Stuck mob just stands there; player
  can close distance.

- **Mob wanders out of room into corridor**: Allowed. Enemy AI does not
  enforce room boundaries after spawn — mobs can roam wherever Hytale's
  native AI takes them.

- **Damage to unknown entity UUID**: Ignore. Could be a native Hytale
  creature or an already-dead mob.

- **Live-mob perf cap**: Soft cap — if total live mobs across all players
  exceeds `MAX_LIVE_MOBS` (default 100), defer spawning. Zone-triggered
  groups stay **armed** and retry next tick until space exists;
  `on_cleared` waves defer until the cap drops below their spawn count.
  Log a warning.

- **Player disconnects mid-wave**: `removePlayer(playerId)` is called.
  Despawn all live mobs for that player, clear all group state, discard
  cached floor data.

- **Cross-player mob interference (multiplayer)**: Mobs are tagged with
  their owning `playerId` in `EnemyState`. Hytale's AI may target a closer
  foreign player, but damage still decrements the owning player's mob
  counter. **Accept for MVP**; if stray targeting causes problems, add
  distance-based despawn of wandering mobs.

---

## 6. Dependencies

### Depends On

- **Floor Generation** — Provides the flattened list of `SpawnGroup` objects
  (with triggers, mob positions, and `nextGroup` chains) via `registerFloor()`.
  Calls `resetMobs()` on player respawn.
- **Combat System** — Calls `applyDamage(entityId, damage)` when player
  attacks hit mobs; reads `archetype.baseAttack` (via
  `EnemyManager.getEnemyState(entityId)`) to compute enemy-to-player damage.
- **Run State Machine** — Notified via `onMobKilled(playerId)` on every
  enemy death; owns `mobsRemaining` counter; Enemy AI only processes damage
  and triggers during `FLOOR_ACTIVE` state.
- **Health & Lives** (indirect) — Enemy attacks flow through
  Combat → HealthManager; Enemy AI never calls Health & Lives directly.

### Depended On By

- **Floor Generation** — Calls `registerFloor()` to install spawn groups;
  expects the returned mob count to pass through to
  `RunStateManager.setMobCount()`.
- **Combat System** — Looks up enemy archetype/attack via
  `EnemyManager.getEnemyState(entityId)`; calls `applyDamage()` to damage
  enemies.
- **Difficulty Scaling** (future) — Will override archetype weights, HP
  variance, and `difficultyMultiplier(floor)` per floor. **Provisional**:
  currently static constants inside Enemy AI.
- **Level Scaling** (future) — May modify player base damage, which affects
  enemy hit-counts (indirect). No direct API coupling.

### Hytale API Dependencies

- **Entity spawning** — Create a Hytale creature entity at a world position
  (archetype → Hytale creature type mapping TBD at implementation)
- **Entity despawning** — Remove a spawned entity cleanly
- **Entity UUID / ID access** — Persistent ID for lookup in `EnemyManager`
- **Native mob AI** — Movement, targeting, attack decisions. Enemy AI relies
  entirely on Hytale's built-in creature behavior; this system does not
  script AI.
- **Damage event interception** — `Damage` event fires on enemy hits; Combat
  System intercepts (not Enemy AI).
- **Entity position read** — For the falling-out-of-world edge case (polling Y).

### Asset Dependencies

- **Spawn-group metadata** — Defined in room template JSON (authored by
  Floor Gen content team). Enemy AI does not own any content assets; it
  consumes Floor Gen's schema.
- **Hytale creature identifiers** — 3 creature IDs (one per archetype)
  chosen at implementation, defined as constants in `EnemyArchetype` enum.

### Cross-System Update Required

After this GDD is approved, update **Floor Generation GDD §3 and §6** to
reference the new spawn-group schema (replacing the simpler "mobSpawns:
list of positions" model).

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MELEE_BASE_HP` | 20 | 5 - 100 | Hits to kill Melee mob. Too low = one-shots trivial; too high = breaks fodder fantasy. |
| `MELEE_BASE_ATK` | 10 | 1 - 50 | Damage per Melee hit. Tune against MAX_HP (200) and typical armor (0-20). |
| `RANGED_BASE_HP` | 12 | 5 - 100 | Hits to kill Ranged mob. Slightly softer than Melee by design. |
| `RANGED_BASE_ATK` | 14 | 1 - 50 | Pressure from ranged pokes. |
| `RUSHER_BASE_HP` | 10 | 5 - 100 | Rusher fragility. Easiest to kill of the three. |
| `RUSHER_BASE_ATK` | 40 | 1 - 50 | Burst damage when rusher closes. ~20% of MAX_HP. |
| `HP_VARIANCE` | 0.30 | 0.0 - 0.5 | Per-spawn HP roll spread. 0 = flat; 0.5 = very swingy. 0.3 hits the "mostly 3, sometimes 2 or 4" feel. |
| `ATK_VARIANCE` | 0.25 | 0.0 - 0.5 | Per-spawn attack roll spread. |
| `WEIGHT_MELEE` | 0.60 | 0.0 - 1.0 | Share of Melee mobs per floor. |
| `WEIGHT_RANGED` | 0.30 | 0.0 - 1.0 | Share of Ranged mobs. |
| `WEIGHT_RUSHER` | 0.10 | 0.0 - 1.0 | Share of Rusher mobs. |
| `MAX_LIVE_MOBS` | 100 | 20 - 500 | Global soft cap on concurrent mobs (perf). Wave spawns defer if exceeded. |
| `FLOOR_Y_FALLOFF` | 5 | 2 - 20 | Blocks below `FLOOR_Y` before a wandering mob is treated as dead. |

**Constraint**: `WEIGHT_MELEE + WEIGHT_RANGED + WEIGHT_RUSHER` must equal
1.0. Changing any one requires re-balancing the others.

**Note**: `difficultyMultiplier(floor)` is owned by Difficulty Scaling
(undesigned), not a tuning knob of Enemy AI. `HP_VARIANCE`, `ATK_VARIANCE`,
and archetype weights **may** be overridden by Difficulty Scaling per
floor; this GDD owns the defaults.

---

## 8. Acceptance Criteria

### Archetypes & Spawning

- [ ] Three archetypes are defined: Melee, Ranged, Rusher — each mapped to
  a specific Hytale creature
- [ ] At spawn time, each mob's archetype is picked via weighted random
  roll (60/30/10 default)
- [ ] Each mob's HP is rolled from `baseHP × uniformRandom(0.7, 1.3)` at spawn
- [ ] Each mob's ATK is rolled from `baseAtk × uniformRandom(0.75, 1.25)` at spawn
- [ ] Weight sum invariant (60 + 30 + 10 → 1.0) is asserted at startup

### Trigger Activation

- [ ] A `zone`-triggered spawn group fires the first tick the player's
  (x, z) enters the zone
- [ ] After firing, the zone is disarmed (one-shot — re-entering does nothing)
- [ ] Two overlapping zones fire independently (both groups spawn) on the
  same tick
- [ ] Zones are NOT checked while the Run State Machine is outside `FLOOR_ACTIVE`

### Wave Chains

- [ ] An `on_cleared` spawn group fires when the referenced group's live
  count reaches 0
- [ ] Wave chains can be 2+ deep (A → B → C)
- [ ] A chain with a missing `nextGroup` ID logs an error and terminates cleanly
- [ ] A cyclic chain is detected at `registerFloor()` time and broken with
  an error log

### Damage & Death

- [ ] `applyDamage(entityId, dmg)` reduces the mob's `currentHP`;
  `currentHP == 0` kills it
- [ ] `applyDamage` on an unknown entity UUID is silently ignored
- [ ] Enemy death despawns the Hytale entity
- [ ] Enemy death calls `runStateManager.onMobKilled(playerId)` exactly once
- [ ] Enemy death decrements its group's live count; `nextGroup` fires if
  count hits 0
- [ ] Hytale's native HP is not used — all damage routes through `EnemyManager`

### Mob Count Reporting

- [ ] `registerFloor()` returns total mob count = sum of `mobs.length`
  across all groups
- [ ] Total count is correct even if some groups are never triggered
  (waves count pre-spawn)

### Cleanup & Reset

- [ ] `resetMobs(playerId)` despawns all live mobs and re-arms all original
  zone triggers
- [ ] `resetMobs` re-rolls archetypes per position (fresh randomness)
- [ ] Floor transition despawns all mobs for that player
- [ ] Player disconnect (`removePlayer`) despawns all mobs and clears state

### Edge Handling

- [ ] A mob falling below `FLOOR_Y - 5` is treated as killed (despawned,
  counter decremented, `onMobKilled` fires)
- [ ] Exceeding `MAX_LIVE_MOBS` soft cap defers new wave group spawns and
  logs a warning
- [ ] Spawn-failure at a blocked position logs an error and skips that mob

### Integration

- [ ] Combat System can read
  `EnemyManager.getEnemyState(entityId).archetype.baseAttack` to compute
  enemy-to-player damage rolls
- [ ] Player hitting a Hytale mob routes through Combat → `applyDamage` →
  HP decrement
- [ ] Enemy AI does not call Health & Lives directly (verified by code review)
