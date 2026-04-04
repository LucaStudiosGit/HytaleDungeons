# System Design: Player Data

> **Status**: Approved
> **Created**: 2026-04-02
> **Systems Index**: design/gdd/systems-index.md (#8)

---

## 1. Overview

Player Data is the per-run data layer that tracks what gear a player has equipped,
their current level, and their XP progress. It holds three equipped item slots
(weapon, armor, crossbow) as item IDs from the Loot & Item Database, plus a player
level that increases as enemies are killed and XP accumulates. All data is
run-scoped — everything resets to defaults on a new run. Other systems query Player
Data to look up the equipped weapon's damage, the armor's damage reduction, the
crossbow's ranged damage, or the player's level for stat scaling. The player never
interacts with this system directly — it's invisible infrastructure behind combat,
upgrades, and the HUD.

---

## 2. Player Fantasy

You don't think about Player Data — you think about *your build*. "I've got the
Mithril Sword and Adamantite Armor, I'm level 7, I can push deeper." The satisfaction
of watching your XP bar fill and leveling up mid-floor after a kill — that's a
mini-reward layered on top of the floor-clearing loop. Every mob kill feels like
progress even before the floor-end upgrade. This system serves "One More Floor"
by making every kill feel meaningful and every level-up a small power spike.

---

## 3. Detailed Rules

### Equipped Slots

| Slot | Category | Default (New Run) | Effect |
|------|----------|-------------------|--------|
| Weapon | weapon | Iron Sword (`iron_sword`) | Determines melee damage via `getEffectiveStat(playerLevel)` |
| Armor | armor | None (empty) | Determines `damageReduction` via `getEffectiveStat(playerLevel)` |
| Crossbow | crossbow | Iron Crossbow (`iron_crossbow`) | Determines ranged damage via `getEffectiveStat(playerLevel)` |

- Each slot holds one item ID (string reference to Loot & Item Database)
- Empty slot = no item in that category (only armor starts empty)
- Equipping an item replaces the current item in that slot; the old item goes to
  the backpack (if room) or is discarded (auto-discard lowest)

### Backpack

- 9 slots, not category-restricted — any mix of weapons, armor, crossbows
- Items in the backpack are **not active** — only equipped items affect stats
- Player can swap between equipped and backpack items via the Inventory UI
- When backpack is full and a new item is acquired (from upgrade selection),
  the weakest item in the backpack (lowest `baseStat` of the same category) is
  automatically discarded to make room

### Player Level & XP

- Player starts each run at **level 1** with **0 XP**
- Killing enemies grants XP (amount defined per enemy type — owned by Enemy AI)
- When XP reaches the level-up threshold, player level increments by 1
- XP and level persist through deaths within a run (only reset on new run)
- Level affects all equipped items via `getEffectiveStat(playerLevel)`
- There is no level cap for MVP (scales indefinitely)

### XP Level-Up Thresholds

- XP required to reach the next level increases per level
- Formula defined in Section 4 (Formulas)

### Player Data API

Other systems interact with Player Data through these methods:

```java
// XP and leveling
grantXP(UUID playerId, int amount)       // called by Combat System after enemy kill
int getPlayerLevel(UUID playerId)         // called by Combat, Health, Level Scaling
int getCurrentXP(UUID playerId)           // called by HUD
int getXPToNextLevel(UUID playerId)       // called by HUD

// Equipped gear
ItemDefinition getEquippedWeapon(UUID playerId)   // called by Combat System
ItemDefinition getEquippedArmor(UUID playerId)    // called by Health & Lives
ItemDefinition getEquippedCrossbow(UUID playerId) // called by Combat System
void equipItem(UUID playerId, int backpackSlot)   // called by Inventory UI
void addToBackpack(UUID playerId, String itemId)  // called by Floor Upgrade Selection

// Lifecycle
void initPlayer(UUID playerId)            // called by Run State Machine on join
void resetPlayer(UUID playerId)           // called by Run State Machine on new run
void removePlayer(UUID playerId)          // called by Run State Machine on disconnect
```

### Run Reset (New Run)

On starting a new run (first join or "New Run" from GAME_OVER):
- Equipped weapon → Iron Sword
- Equipped armor → empty
- Equipped crossbow → Iron Crossbow
- Backpack → empty
- Player level → 1
- XP → 0

### Interactions with Other Systems

| System | Interaction |
|--------|------------|
| **Loot & Item Database** | Player Data stores item IDs; looks up ItemDefinition for stat queries |
| **Combat System** | Reads equipped weapon/crossbow effective stat for damage calculation |
| **Health & Lives** | Reads equipped armor effective stat for damage reduction |
| **Run State Machine** | Triggers reset on new run; provides `currentFloor` context |
| **Floor Upgrade Selection** | Writes new items to backpack/equipped when player picks upgrade |
| **Inventory UI** | Reads equipped + backpack items for display; writes equip/swap actions |
| **Level Scaling** | Reads player level for enemy difficulty scaling |
| **HUD** | Reads player level, XP, XP-to-next-level for display |

---

## 4. Formulas

### XP Required for Next Level

```
xpToNextLevel = BASE_XP + (XP_PER_LEVEL * currentLevel)
```

Where:
- `BASE_XP = 50` — base component of the XP curve formula
- `XP_PER_LEVEL = 25` — additional XP required per level
- `currentLevel` — player's current level (starts at 1)

| Level | XP to Next | Cumulative XP |
|-------|-----------|---------------|
| 1 → 2 | 75 | 75 |
| 2 → 3 | 100 | 175 |
| 3 → 4 | 125 | 300 |
| 4 → 5 | 150 | 450 |
| 5 → 6 | 175 | 625 |
| 10 → 11 | 300 | 1,925 |

### XP from Kills

```
xpGained = enemyBaseXP
```

Where `enemyBaseXP` is defined per enemy type (owned by Enemy AI system). Placeholder
values for design estimation:
- Basic enemy: ~10 XP
- Tough enemy: ~25 XP
- Elite enemy: ~50 XP

At ~10 XP per kill and ~10 enemies per floor, a player earns ~100 XP per floor.
Pacing estimate:
- Floor 1: 100 XP → level 2 (75 used, 25 carry-over)
- Floor 2: 125 XP total → level 3 (100 used, 25 carry-over)
- Floor 3: 125 XP total → level 4 (125 used, 0 carry-over)
- Floor 5: ~level 5
- Floor 10: ~level 8

At level 5, gear stats scale by `1.0 + 0.1 * 5 = 1.5x` — a meaningful but not
overwhelming boost. Players gain roughly 1 level per floor in early game, slowing
to 1 level per 2 floors later.

### Auto-Discard Selection

When backpack is full and a new item is acquired:
```
discardTarget = item in backpack with lowest baseStat where item.category == newItem.category
```

If no items of the same category exist in the backpack, discard the item with the
lowest `baseStat` regardless of category.

### Effective Stat Queries

Player Data does not own the effective stat formula — it delegates to ItemDefinition:
```
equippedWeapon.getEffectiveStat(playerLevel)   // from Loot system
equippedArmor.getEffectiveStat(playerLevel)    // from Loot system
equippedCrossbow.getEffectiveStat(playerLevel) // from Loot system
```

---

## 5. Edge Cases

- **Player has no armor equipped**: `damageReduction = 0` — full raw damage applies
  (consistent with Health & Lives GDD)
- **Player has no weapon equipped**: Falls back to FISTS (baseStat=1) from Loot system
- **Backpack full, no items of same category to discard**: Discard the item with the
  lowest `baseStat` in the entire backpack regardless of category
- **Backpack full, all items have equal baseStat**: Discard the first (oldest acquired)
  item found with that baseStat
- **Player levels up mid-combat**: Level increase takes effect immediately — next
  attack uses the new level for `getEffectiveStat`. No animation delay.
- **XP earned while dead (DEAD state)**: Not possible — combat is disabled during
  DEAD. No XP awarded.
- **Overflow XP past level threshold**: Excess XP carries over. If threshold is 100
  and player earns 130 XP, they level up with 30 XP toward the next level.
- **Player swaps weapon mid-cooldown**: Cooldown timer continues (owned by Combat
  System). New weapon's cooldownMs applies to the *next* attack after the current
  cooldown expires.
- **Item in backpack references an ID not in ItemDatabase**: Return FISTS fallback.
  Log a warning. This shouldn't happen if data is consistent.
- **Player disconnects**: All Player Data is discarded with the run (same as RunData)
- **Equipping an item when backpack is full**: The old equipped item replaces the new
  item's backpack slot (direct swap, no discard needed)

---

## 6. Dependencies

- **Depends on**:
  - Loot & Item Database — source of truth for item definitions; Player Data stores
    IDs and delegates stat queries to ItemDefinition
- **Depended on by**:
  - Combat System — reads equipped weapon/crossbow + player level for damage calc
  - Health & Lives — reads equipped armor + player level for damage reduction
  - Floor Upgrade Selection — writes new items to equipped/backpack on upgrade pick
  - Inventory UI — reads equipped + backpack items for display; writes equip/swap
  - Level Scaling — reads player level for enemy difficulty scaling
  - HUD — reads level, XP, XP-to-next for display
  - Run State Machine — calls reset on new run
- **Hytale API dependencies**: None specific — this is a pure data layer. Hytale
  inventory (used by RunStateManager for visual equipping) is separate from our
  logical tracking.

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `BASE_XP` | 50 | 20 - 200 | XP to reach level 2. Lower = faster early levels. |
| `XP_PER_LEVEL` | 25 | 10 - 100 | XP increase per level. Higher = slower late-game leveling. |
| `BACKPACK_SIZE` | 9 | 3 - 15 | Inventory slots. Fewer = more discard pressure. More = hoarding. |
| `DEFAULT_WEAPON` | `iron_sword` | Any valid weapon ID | Starting weapon for new runs. |
| `DEFAULT_CROSSBOW` | `iron_crossbow` | Any valid crossbow ID | Starting crossbow for new runs. |
| `DEFAULT_ARMOR` | `null` (empty) | Any valid armor ID or null | Starting armor. Null = no armor. |

Note: `LEVEL_SCALE_FACTOR` (0.1) is owned by the Loot system, not duplicated here.
Enemy XP values are owned by Enemy AI, not duplicated here.

---

## 8. Acceptance Criteria

- [ ] New run starts with Iron Sword equipped, no armor, Iron Crossbow equipped
- [ ] New run starts with empty backpack, level 1, 0 XP
- [ ] Killing an enemy awards XP (amount from enemy type definition)
- [ ] XP reaching threshold increments player level by 1
- [ ] Overflow XP carries into next level
- [ ] Level-up immediately affects `getEffectiveStat()` results for all equipped items
- [ ] XP formula: level 1→2 requires 75 XP, level 5→6 requires 175 XP
- [ ] Equipping a backpack item swaps it with the current equipped item in that slot
- [ ] Backpack holds up to 9 items of any category mix
- [ ] When backpack is full and new item acquired, lowest baseStat item of same category is auto-discarded
- [ ] XP and level persist through player death (DEAD → respawn)
- [ ] All player data resets on new run (GAME_OVER → new run)
- [ ] Player disconnect cleans up all player data
- [ ] Empty armor slot results in 0 damage reduction
- [ ] No weapon equipped falls back to FISTS (1 base damage)
