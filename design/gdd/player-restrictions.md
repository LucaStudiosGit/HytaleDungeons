# System Design: Player Restrictions

*Created: 2026-04-01*
*Status: Draft*
*Systems Index: design/gdd/systems-index.md (#3)*

---

## 1. Overview

The Player Restrictions system disables default Hytale behaviors that conflict
with the dungeon crawler experience. Each restriction is a separate class,
registered through a central coordinator. The system is extensible — new
restrictions are added by creating a new class and registering it.

---

## 2. Player Fantasy

The game feels like a dungeon crawler, not a sandbox. You can't accidentally
break blocks, open the wrong inventory, or jump out of combat. The controls
are locked to exactly what a dungeon crawler needs — nothing more, nothing less.

---

## 3. Detailed Rules

### Active Restrictions (MVP)

| Restriction | Class | What It Blocks | Why |
|-------------|-------|---------------|-----|
| No Block Breaking | `NoBreakBlockRestriction` | Player cannot break/mine blocks | Not a sandbox game |
| No Block Damage | `NoDamageBlockRestriction` | Player cannot damage blocks | Not a sandbox game |
| No Hotbar Switching | `NoHotbarSwitchRestriction` | Player cannot manually switch hotbar slots | Gear is managed by the game, not manually |
| No Jumping | `NoJumpRestriction` | Player cannot jump | Top-down dungeon crawler, jumping breaks movement |
| No Default Inventory | `InventoryOpenDisabler` | Intercepts inventory open packet, redirects to custom page | Default Hytale inventory is replaced by custom UI |

### Architecture

```
PlayerRestrictions (coordinator)
├── NoBreakBlockRestriction
├── NoDamageBlockRestriction
├── NoHotbarSwitchRestriction
├── NoJumpRestriction
└── InventoryOpenDisabler
```

- Each restriction is an independent class with a `register()` method
- `PlayerRestrictions` is the coordinator — calls `register()` on all restrictions
- Adding a new restriction: create the class, add one line to `PlayerRestrictions.register()`
- Each restriction listens to the relevant Hytale event and cancels/intercepts it

### Inventory Interception

The `InventoryOpenDisabler` uses reflection to hook into `GenericPacketHandler`
and intercept `ClientOpenWindow` packets of type `PocketCrafting`. When intercepted:
1. The default inventory is suppressed
2. A custom `InventoryPage` (HyUI) is opened instead
3. Forced close packets are tracked to prevent double-close issues

### Future Restrictions (not MVP, but anticipated)

| Restriction | What It Would Block | When Needed |
|-------------|-------------------|-------------|
| No Block Placing | Player cannot place blocks | If not covered by existing restrictions |
| No Chat Commands | Block certain commands during runs | If players find exploits |
| No PvP Damage | Block player-to-player damage | Co-op v2 |
| No Item Dropping | Prevent dropping items on the ground | If players try to dupe/exploit |

---

## 4. Formulas

No formulas — this system is binary (restricted or not). No scaling or calculation.

---

## 5. Edge Cases

- **Restriction fails to register**: Log a warning. The game is playable but the
  player might access unintended features. Non-fatal.
- **Packet handler is not GenericPacketHandler**: `InventoryOpenDisabler` logs a
  warning and skips hooking for that player. They'll see the default inventory.
- **Player disconnects with pending inventory close**: `InventoryOpenDisabler`
  cleans up tracked state on `PlayerDisconnectEvent`.
- **Restriction conflicts with game system**: If a future system needs to
  temporarily allow a restricted action (e.g., hotbar switch during upgrade),
  the restriction class should expose `enable()`/`disable()` methods per player.
- **Multiple restrictions on same event**: Each restriction handles its own event
  independently — no conflicts since they block different things.

---

## 6. Dependencies

- **Depended on by**: All gameplay systems rely on restrictions being active to
  maintain the dungeon crawler experience
- **Depends on**: Nothing — foundation system
- **Hytale API dependency**: Event registry for game events, `GenericPacketHandler`
  (reflection-based) for inventory interception, HyUI for custom inventory page
- **Existing implementation**: All 5 MVP restrictions are implemented. The
  `InventoryOpenDisabler` is currently commented out in `Main.java` — verify it
  still works before re-enabling (it was disabled for a reason — may need debugging).
- **Technical risk**: `InventoryOpenDisabler` uses reflection on
  `GenericPacketHandler.handlers` — this is fragile and may break across Hytale
  updates. If it breaks, the fallback is that players see the default inventory
  (non-fatal but immersion-breaking).

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| Per-restriction enable/disable | All enabled | on/off per restriction | Allows toggling restrictions for debugging or future features |

No numeric tuning — restrictions are binary.

---

## 8. Acceptance Criteria

- [ ] Player cannot break or damage blocks
- [ ] Player cannot jump
- [ ] Player cannot manually switch hotbar slots
- [ ] Pressing inventory key opens custom inventory page, not default Hytale inventory
- [ ] Custom inventory page has a working exit button (hover, press, close states)
- [ ] Player disconnect cleans up all restriction state (no memory leaks)
- [ ] Adding a new restriction requires only: 1 new class + 1 line in coordinator
- [ ] All restrictions activate on player join without errors
- [ ] `InventoryOpenDisabler` is re-enabled in `Main.java`
