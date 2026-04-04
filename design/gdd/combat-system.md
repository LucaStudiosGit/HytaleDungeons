# System Design: Combat System

> **Status**: Approved
> **Created**: 2026-04-02
> **Systems Index**: design/gdd/systems-index.md (#7)

---

## 1. Overview

The Combat System connects Hytale's built-in attack mechanics to our damage pipeline.
Melee uses Hytale's native left-click attack — we intercept the damage event and
route it through the Health & Lives system with the equipped weapon's effective stat.
Ranged uses the existing RightClickCrossbowHandler — right-click fires a crossbow
projectile whose damage is based on the equipped crossbow's effective stat. Both
melee and ranged damage from enemies flows through the same Health & Lives pipeline
with armor reduction. This system is the bridge between "things hitting each other"
and "numbers changing."

---

## 2. Player Fantasy

Swing your sword and mobs crumble. Fire your crossbow and they drop at range.
Combat is fast, satisfying, and never makes you think about numbers — you just feel
more powerful as your gear improves. Serves "Instant Action": the game is combat,
and combat should feel like the reason you play.

---

## 3. Detailed Rules

### Player Melee Attack

1. Player left-clicks → Hytale triggers melee attack animation
2. Hytale fires a `Damage` event when the hit connects
3. We intercept the `Damage` event and cancel Hytale's native damage
4. Calculate our damage: `weaponEffectiveStat` from equipped weapon
5. Apply damage to enemy's custom HP tracker
6. If enemy HP ≤ 0: kill the enemy, notify Run State Machine (`onMobKilled`)

### Player Ranged Attack

1. Player right-clicks → `RightClickCrossbowHandler` fires projectile
2. Projectile hits enemy → `Damage` event fires
3. Same interception: cancel native damage, apply `crossbowEffectiveStat`
4. Same kill flow as melee

### Enemy Attacks Player

1. Enemy melee/ranged hits player → `Damage` event fires
2. Intercept: cancel native damage
3. Read enemy's `attackDamage` value (defined per enemy type)
4. Call `HealthManager.takeDamage(playerId, playerRef, rawDamage, damageReduction)`
5. Health system handles armor reduction and death

### Attack Speed

- Each weapon/crossbow defines its own `cooldownMs` in the item database
- After attacking, the player cannot attack again until the equipped weapon's
  cooldown expires
- Cooldown is read from the currently equipped item's definition
- During cooldown, left-click melee and right-click ranged inputs are blocked
  (the attack does not fire at all — no animation, no swing, no projectile)

### Knockback

- Disabled for MVP — no knockback on hit for either player or enemies

### Combat Enable/Disable

- Combat is only active during `FLOOR_ACTIVE` state (Run State Machine)
- During other states, damage events are ignored

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Health & Lives** | Calls `takeDamage()` for player damage; owns player death |
| **Loot & Item Database** | Reads weapon/crossbow `getEffectiveStat()` for damage values |
| **Run State Machine** | Combat only active during FLOOR_ACTIVE; notified via `onMobKilled()` |
| **Player Controller** | Player position/facing for hit detection |
| **Enemy AI** | Enemies trigger attacks; Combat System processes the damage |

---

## 4. Formulas

### Player → Enemy Damage

```
meleeDamage = equippedWeapon.getEffectiveStat(playerLevel, LEVEL_SCALE_FACTOR)
rangedDamage = equippedCrossbow.getEffectiveStat(playerLevel, LEVEL_SCALE_FACTOR)
```

Uses the Loot system formula:
`effectiveStat = baseStat * rarityMultiplier * (1.0 + LEVEL_SCALE_FACTOR * playerLevel)`

Result is floored to integer.

### Enemy → Player Damage

Handled entirely by Health & Lives:
```
actualDamage = max(1, enemyAttackDamage - playerDamageReduction)
```

### Attack Cooldown

```
canAttack = (currentTimeMs - lastAttackTimeMs) >= equippedItem.cooldownMs
```

Where `cooldownMs` is defined per-item in `config/items.json`.

### Example: Melee

Iron Sword (baseStat=8), Common (1.0x), level 1:
```
meleeDamage = 8 * 1.0 * (1.0 + 0.1 * 1) = 8.8 → 8
```

### Example: Ranged

Iron Crossbow (baseStat=9), Common (1.0x), level 5:
```
rangedDamage = 9 * 1.0 * (1.0 + 0.1 * 5) = 13.5 → 13
```

---

## 5. Edge Cases

- **Player has no weapon equipped**: Use "Fists" default from Loot system (baseStat=1)
- **Player has no crossbow equipped**: Right-click does nothing (RightClickCrossbowHandler
  already handles this — no crossbow item in slot = no shot)
- **Player attacks during non-FLOOR_ACTIVE state**: Damage event cancelled, no effect
- **Enemy attacks player during non-FLOOR_ACTIVE state**: Damage event cancelled
- **Attack cooldown during weapon swap**: Cooldown timer continues across swaps.
  Swapping weapons doesn't reset cooldown.
- **Multiple enemies hit by one swing**: Hytale melee may hit multiple entities.
  Process each hit independently through damage pipeline.
- **Projectile hits player instead of enemy**: Friendly fire disabled — projectiles
  from the player can only damage enemies
- **Enemy dies from overkill damage**: Excess damage is discarded. Enemy dies, mob
  counter decrements.
- **Damage event from fall damage or environment**: Ignore non-combat damage sources
  for MVP (cancel fall damage via Player Restrictions if needed)

---

## 6. Dependencies

- **Depends on**:
  - Player Controller — player entity position/facing
  - Health & Lives — `takeDamage()` for player damage, death flow
  - Loot & Item Database — weapon/crossbow `getEffectiveStat()` for damage values
  - Player Data — provides equipped weapon/crossbow and player level for damage calc
- **Depended on by**:
  - Enemy AI — enemies trigger attacks processed by this system
- **Hytale API dependencies**:
  - `Damage` event (CancellableEcsEvent) for intercepting all damage
  - `DamageSource` to distinguish player vs. enemy vs. environment damage
  - `RightClickCrossbowHandler` (existing) for ranged attack projectiles
- **Existing implementation**: `RightClickCrossbowHandler.java` handles projectile
  spawning. Needs wiring to read damage from Loot system instead of hardcoded values.

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| Per-item `cooldownMs` | 400 (melee) / 600 (ranged) | 200 - 1200 | Attack speed per weapon. Defined in `config/items.json`. |
| `LEVEL_SCALE_FACTOR` | 0.1 | 0.05 - 0.2 | From Loot system — affects all damage scaling. Not duplicated here. |
| Per-weapon `baseStat` | See Loot GDD | 1 - 30 | Weapon base damage. Tuned in `config/items.json`. |

---

## 8. Acceptance Criteria

- [ ] Left-click melee deals weapon's effective stat as damage to enemies
- [ ] Right-click ranged deals crossbow's effective stat as damage to enemies
- [ ] Enemy attacks deal raw damage processed through Health & Lives (with armor)
- [ ] No weapon equipped → melee deals 1 damage (Fists default)
- [ ] Attack cooldown per weapon prevents spamming (read from item data)
- [ ] Combat disabled during non-FLOOR_ACTIVE states (no damage processed)
- [ ] Enemy reaching 0 HP triggers death and `onMobKilled()` on Run State Machine
- [ ] No knockback on hit (MVP)
- [ ] Hytale's native damage is cancelled — all damage routes through our pipeline
- [ ] Friendly fire disabled — player projectiles don't damage the player
