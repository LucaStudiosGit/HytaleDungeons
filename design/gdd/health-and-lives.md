# System Design: Health & Lives

> **Status**: Approved
> **Created**: 2026-04-02
> **Systems Index**: design/gdd/systems-index.md (#6)

---

## 1. Overview

The Health & Lives system tracks player hit points (HP) and communicates death to
the Run State Machine. The player has a fixed max HP pool. Damage from enemies
reduces HP; at 0 HP, a death event fires. HP resets to full on respawn and on
entering a new floor. A health potion provides in-floor healing with a cooldown
between uses. The lives counter is owned by the Run State Machine — this system
only owns HP.

---

## 2. Player Fantasy

You always know how close you are to death. The health bar is a constant pressure
gauge — low HP means every hit matters, every potion is precious. When you chug a
potion and survive a room with a sliver of health, that's the rush. The system
serves "One More Floor" by making survival feel earned, not guaranteed.

---

## 3. Detailed Rules

### HP Pool

- Max HP: `MAX_HP = 200`
- HP resets to `MAX_HP` on: respawn after death, entering a new floor
- HP cannot exceed `MAX_HP` (healing is capped, excess is wasted)
- At 0 HP → fire `onPlayerDeath` event to Run State Machine

### Damage

- Incoming damage is reduced by armor's `damageReduction` stat (from Loot system)
- `actualDamage = max(1, rawDamage - damageReduction)`
- Minimum 1 damage — armor cannot fully negate hits
- `newHP = max(0, currentHP - actualDamage)`

### Health Potion

- Activated by pressing Q (dedicated key, not a hotbar item)
- Heals `POTION_HEAL_AMOUNT` HP instantly
- Cooldown: `POTION_COOLDOWN` seconds between uses
- Unlimited uses — no charge tracking
- Can be used at full HP — the heal is wasted but cooldown still triggers
- Cannot use during cooldown (show cooldown timer on HUD)

### HP Reset Points

- On respawn (DEAD → FLOOR_ACTIVE): HP = MAX_HP
- On new floor (DESCENDING → FLOOR_ACTIVE): HP = MAX_HP
- On new run (GAME_OVER → FLOOR_ACTIVE): HP = MAX_HP

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Run State Machine** | This system fires `onPlayerDeath(playerId, playerRef)` when HP reaches 0 |
| **Combat System** | Combat system calls `takeDamage(playerId, rawDamage)` when player is hit |
| **Loot & Item Database** | Reads equipped armor's `damageReduction` effective stat |
| **HUD** | Provides `currentHP`, `maxHP`, `potionCooldownRemaining` for display |
| **Enemy AI** | Enemies deal damage through Combat System, not directly |

---

## 4. Formulas

### Damage Taken

```
actualDamage = max(1, rawDamage - damageReduction)
newHP = max(0, currentHP - actualDamage)
```

Where:
- `rawDamage`: incoming damage from Combat System (int, range 1-50+)
- `damageReduction`: equipped armor's effective stat from Loot system (int, range 0-20)
- Minimum 1 damage ensures armor cannot fully negate hits

### Potion Healing

```
newHP = min(MAX_HP, currentHP + POTION_HEAL_AMOUNT)
```

### Constants

```
MAX_HP = 200
POTION_HEAL_AMOUNT = 50 (25% of max HP)
POTION_COOLDOWN = 8.0 seconds
```

### Example: Damage

Player at 120 HP, hit for 30 raw damage, iron armor (damageReduction=6):
```
actualDamage = max(1, 30 - 6) = 24
newHP = max(0, 120 - 24) = 96
```

### Example: Potion at Near-Full HP

Player at 170 HP uses potion:
```
newHP = min(200, 170 + 50) = 200 (30 HP wasted)
```

---

## 5. Edge Cases

- **Damage exceeds current HP**: HP floors at 0, death event fires once
- **Multiple damage sources in same tick**: Process sequentially; if first hit
  kills, subsequent hits are ignored (player is already dead)
- **Potion used at exactly 0 HP**: Not possible — death event fires instantly at
  0 HP, state transitions to DEAD (input disabled)
- **Potion used during DEAD/GAME_OVER state**: Ignored — potion only works during
  FLOOR_ACTIVE
- **Damage during non-FLOOR_ACTIVE states**: Ignored — combat is disabled
- **Armor not equipped (no damageReduction)**: damageReduction = 0, full raw damage
  applies
- **rawDamage is 0 or negative**: Clamp to minimum 0 before formula (no healing
  from "damage")
- **Cooldown timer across floor transitions**: Cooldown resets on new floor /
  respawn

---

## 6. Dependencies

- **Depends on**:
  - Player Controller — provides player entity access
  - Run State Machine — this system fires `onPlayerDeath`; Run State Machine calls
    `resetHP()` on respawn/new floor. Run State Machine state determines whether
    damage and potions are processed (FLOOR_ACTIVE only).
  - Loot & Item Database — reads equipped armor's `damageReduction` effective stat
  - Player Data — provides equipped armor reference and player level for damage reduction calc
- **Depended on by**:
  - Combat System — calls `takeDamage(playerId, rawDamage)` to apply damage
  - Enemy AI — enemies deal damage through Combat System, not directly
  - HUD — reads `currentHP`, `maxHP`, `potionCooldownRemaining` for display
- **Hytale API dependencies**: Key input event for potion key (Q), player entity
  health manipulation (or custom tracking if Hytale's built-in HP is unsuitable)

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `MAX_HP` | 200 | 50 - 500 | Player survivability. Too low = one-shot deaths. Too high = no tension. |
| `POTION_HEAL_AMOUNT` | 50 | 10 - 100 | Healing per use. Should be 15-35% of MAX_HP for meaningful but not full recovery. |
| `POTION_COOLDOWN` | 8.0s | 3.0 - 15.0s | Time between potion uses. Too short = unkillable. Too long = useless. |
| `MIN_DAMAGE` | 1 | 1 | Floor on damage after armor reduction. Keep at 1 to prevent full immunity. |

---

## 8. Acceptance Criteria

- [ ] Player starts with 200 HP on run start
- [ ] Taking 30 raw damage with 6 armor → HP decreases by 24
- [ ] Taking 5 raw damage with 10 armor → HP decreases by 1 (minimum damage)
- [ ] HP reaching 0 fires `onPlayerDeath` to Run State Machine exactly once
- [ ] Pressing Q heals 50 HP (capped at MAX_HP)
- [ ] Potion at full HP: HP stays at 200, cooldown triggers, heal is wasted
- [ ] Potion cooldown prevents use for 8 seconds after use
- [ ] HP resets to MAX_HP on respawn (DEAD → FLOOR_ACTIVE)
- [ ] HP resets to MAX_HP on new floor (DESCENDING → FLOOR_ACTIVE)
- [ ] Potion cooldown resets on new floor / respawn
- [ ] Damage during non-FLOOR_ACTIVE states is ignored
- [ ] Potion during non-FLOOR_ACTIVE states is ignored
- [ ] No armor equipped → full raw damage applies (damageReduction = 0)
- [ ] HUD can read currentHP, maxHP, and potion cooldown remaining
