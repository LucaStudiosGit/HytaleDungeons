# System Design: Run State Machine

> **Status**: Approved
> **Created**: 2026-04-02
> **Systems Index**: design/gdd/systems-index.md (#5)

---

## 1. Overview

The Run State Machine governs the lifecycle of a dungeon run from start to finish.
It tracks the player's current state — fighting on a floor, choosing an upgrade,
dead (respawning), or game over — and tells other systems what to allow or block.

A run begins the moment a player joins (direct spawn into floor 1, no lobby). On
death, a brief screen shows lives remaining before restarting the floor. On game
over (3 deaths), a score screen shows floor reached and offers a new run or
disconnect.

This is the central coordinator: it enables/disables movement, triggers floor
generation, opens the upgrade UI, and manages the life counter.

---

## 2. Player Fantasy

You never think about the Run State Machine — it's invisible infrastructure. What
you *feel* is seamless flow: floor ends, upgrade appears, you pick, next floor
starts. Die? Brief pause, you're back. Game over? See how far you got, hit "again."

The system serves "One More Floor" by making every transition instant and
frictionless. The player's attention stays on combat and choices, never on loading
or waiting.

---

## 3. Detailed Rules

### States

| State | Description | Movement | Combat | UI |
|-------|-------------|----------|--------|----|
| **FLOOR_ACTIVE** | Player is fighting on a floor | Enabled | Enabled | HUD visible |
| **UPGRADING** | Floor cleared + exit reached, choosing upgrade | Disabled | Disabled | Upgrade selection UI |
| **DESCENDING** | Transition to next floor | Disabled | Disabled | Floor number display |
| **DEAD** | Player died, death screen | Disabled | Disabled | Death screen (lives left) |
| **GAME_OVER** | All lives spent | Disabled | Disabled | Score screen |

### Transitions

```
[Player Joins] → FLOOR_ACTIVE (floor 1, 3 lives)
FLOOR_ACTIVE → UPGRADING       (all mobs dead AND player reaches exit)
UPGRADING → DESCENDING          (player picks upgrade)
DESCENDING → FLOOR_ACTIVE       (next floor generated & loaded)
FLOOR_ACTIVE → DEAD             (player HP reaches 0)
DEAD → FLOOR_ACTIVE             (after death screen, restart same floor, lives--)
DEAD → GAME_OVER                (lives == 0)
GAME_OVER → FLOOR_ACTIVE        (new run — floor 1, 3 lives, gear reset)
GAME_OVER → [Disconnect]        (player quits)
```

### Floor Completion Conditions

A floor is complete when **both** conditions are met during FLOOR_ACTIVE:
1. `mobsRemaining == 0` — all enemies on the floor are dead
2. Player enters the exit zone — collision trigger at the floor exit

Reaching the exit with mobs alive does nothing. Killing all mobs without reaching
the exit keeps the floor active (player can explore freely).

### Run Data (Per Player)

Each active run tracks:
- `currentFloor` (int, starts at 1)
- `livesRemaining` (int, starts at `MAX_LIVES`)
- `currentState` (enum: FLOOR_ACTIVE, UPGRADING, DESCENDING, DEAD, GAME_OVER)
- `mobsRemaining` (int, set when floor is generated)

### State Behavior

**FLOOR_ACTIVE**: Movement enabled, combat enabled, HUD shows floor number and
lives. The player fights mobs and explores. Transition out on death or floor
completion.

**UPGRADING**: Movement disabled, combat disabled. The Upgrade Selection UI
presents 3 options (weapon, armor, or crossbow). Player picks one; the item is
equipped. Transition to DESCENDING.

**DESCENDING**: Movement disabled. Brief visual transition (floor number display).
Floor Generation creates the next floor. Player is teleported to the new floor's
spawn point. Transition to FLOOR_ACTIVE when ready.

**DEAD**: Movement disabled. Death screen shows for `DEATH_SCREEN_DURATION` seconds,
displaying lives remaining. If `livesRemaining > 0`: decrement lives, reset floor
mobs, teleport to floor spawn, transition to FLOOR_ACTIVE. If `livesRemaining == 0`:
transition to GAME_OVER.

**GAME_OVER**: Score screen shows floor reached. Two buttons: "New Run" (resets
everything, transitions to FLOOR_ACTIVE on floor 1) and "Quit" (disconnects player).

### Default Loadout (New Run)

When starting a new run (first join or "New Run" from GAME_OVER):
- **Weapon**: Iron Sword (`iron_sword`)
- **Crossbow**: Iron Crossbow (`iron_crossbow`)
- **Armor**: None (empty slot)

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Player Controller** | Run State Machine calls `setMovementEnabled(false)` in all states except FLOOR_ACTIVE |
| **Health & Lives** | Health system fires `onPlayerDeath` event → Run State Machine transitions to DEAD |
| **Floor Generation** | Run State Machine requests floor generation during DESCENDING transition |
| **Combat System** | Run State Machine enables/disables combat processing per state |
| **Floor Upgrade Selection** | Run State Machine opens upgrade UI in UPGRADING state, receives callback when player picks |
| **HUD** | Run State Machine provides current floor number and lives remaining |
| **Enemy AI** | Enemies notify Run State Machine when killed (decrement `mobsRemaining`) |
| **Loot & Item Database** | On GAME_OVER → "New Run": equipped items are reset to defaults |

---

## 4. Formulas

This system is state logic, not math-heavy. Core constants:

```
MAX_LIVES = 3
STARTING_FLOOR = 1
DEATH_SCREEN_DURATION = 3.0 seconds
DESCENDING_TRANSITION_DURATION = 2.0 seconds
```

Difficulty scaling, mob counts, and loot scaling are owned by other systems
(Difficulty Scaling, Floor Generation, Level Scaling). The Run State Machine
only provides `currentFloor` as input to those systems.

---

## 5. Edge Cases

- **Player disconnects mid-run**: Run data is discarded. No save/resume for MVP —
  reconnecting starts a fresh run.
- **Player dies during DESCENDING transition**: Not possible — combat is disabled
  during DESCENDING.
- **Player kills last mob while standing on exit**: Both conditions met
  simultaneously — transition to UPGRADING immediately.
- **Player tries to re-enter cleared rooms**: Allowed during FLOOR_ACTIVE. Mobs
  don't respawn on a cleared floor.
- **"New Run" during GAME_OVER**: Reset all run data (floor=1, lives=3,
  gear=defaults). Old floor is cleaned up before generating floor 1.
- **Only one player per run (MVP)**: If multiple players join the server, each has
  an independent run state. No shared state.
- **Death screen timer expires exactly when lives reach 0**: Check lives *before*
  respawning — transition to GAME_OVER, never briefly respawn.

---

## 6. Dependencies

- **Depends on**: Player Controller (calls `setMovementEnabled` to freeze/unfreeze
  player during transitions)
- **Depended on by**:
  - Health & Lives — fires `onPlayerDeath` event, Run State Machine transitions to DEAD
  - Floor Generation — Run State Machine requests new floor during DESCENDING
  - Difficulty Scaling — reads `currentFloor` to scale mob difficulty
  - Floor Upgrade Selection — Run State Machine opens/closes upgrade flow in UPGRADING
  - HUD — reads current floor number and lives remaining
  - Combat System — Run State Machine enables/disables combat per state
- **Hytale API dependencies**: `PlayerReadyEvent` (run start), `PlayerDisconnectEvent`
  (cleanup), teleportation API (respawn/floor transitions), scheduled executor or
  tick-based timer (death screen / descending duration)

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MAX_LIVES` | 3 | 1 - 5 | Total deaths before game over. Too low = frustrating. Too high = no tension. |
| `DEATH_SCREEN_DURATION` | 3.0s | 1.0 - 5.0s | Time on death screen. Too short = jarring. Too long = annoying. |
| `DESCENDING_TRANSITION_DURATION` | 2.0s | 1.0 - 4.0s | Time for floor transition. Covers floor generation loading. |
| `STARTING_FLOOR` | 1 | 1+ | Debug/testing only — skip to a later floor. Always 1 in production. |

---

## 8. Acceptance Criteria

- [ ] Player joins server → immediately in FLOOR_ACTIVE on floor 1 with 3 lives
- [ ] Player death → DEAD state, death screen shows for `DEATH_SCREEN_DURATION`
- [ ] Lives decrement on respawn, not on death (prevents off-by-one with GAME_OVER check)
- [ ] `livesRemaining == 0` on death → GAME_OVER, never respawns
- [ ] Movement and combat are disabled in all states except FLOOR_ACTIVE
- [ ] All mobs dead + player at exit → transition to UPGRADING
- [ ] Reaching exit with mobs alive → nothing happens
- [ ] Player picks upgrade → DESCENDING → new floor generated → FLOOR_ACTIVE
- [ ] GAME_OVER screen shows floor reached, offers "New Run" and "Quit"
- [ ] "New Run" resets floor to 1, lives to 3, gear to defaults
- [ ] Player disconnect during any state → run data cleaned up, no leaks
- [ ] State transitions fire events that other systems can listen to

---

## 9. UI Requirements

### FLOOR_ACTIVE HUD (always visible)

- **Floor number**: Top center — "Floor 7"
- **Lives remaining**: Top left — 3 heart icons (filled = alive, empty = spent)
- **Mobs remaining**: Below floor number — "12 enemies left" (updates as mobs die)

### DEAD Screen (overlay, auto-dismiss)

- Screen dims/darkens
- "You Died" text center screen
- Lives remaining shown as hearts (one fewer filled)
- Auto-dismisses after `DEATH_SCREEN_DURATION` seconds

### GAME_OVER Screen (overlay, interactive)

- "Game Over" text center screen
- "Floor Reached: X" below
- Two buttons: **New Run** / **Quit**

### UPGRADING Screen (overlay, interactive)

- 3 item cards side by side (weapon, armor, crossbow)
- Each card: item icon, name, rarity color, stat comparison vs currently equipped
- Player clicks one to select — detail owned by Floor Upgrade Selection UI system

### DESCENDING Transition (fullscreen, auto-dismiss)

- Fullscreen overlay: "Floor X" in large text
- Fades out as new floor loads
- Duration: `DESCENDING_TRANSITION_DURATION` seconds
