# System Design: Loot & Item Database

*Created: 2026-04-01*
*Status: Draft*
*Systems Index: design/gdd/systems-index.md (#4)*

---

## 1. Overview

The Loot & Item Database is the single source of truth for every piece of gear
in Hytale Dungeons. It defines weapons, armor, and crossbows as data — loaded
from JSON config, not hardcoded. Every system that touches items (combat, upgrades,
inventory, scaling) reads from this database.

---

## 2. Player Fantasy

Every floor upgrade feels meaningful because the gear you pick has visible,
immediate impact. You see the numbers go up, you feel the difference in combat.
Simple enough to understand at a glance — no stat spreadsheets, no analysis
paralysis.

---

## 3. Detailed Rules

### Item Categories

| Category | Slot | Primary Stat | Effect |
|----------|------|-------------|--------|
| Weapon | Weapon | `meleeDamage` | Damage dealt by melee attacks |
| Armor | Armor | `damageReduction` | Flat damage reduction on hits taken |
| Crossbow | Crossbow | `rangedDamage` | Damage dealt by crossbow projectiles |

### Item Definition Schema

Each item is defined in JSON with these fields:

```json
{
  "id": "iron_sword",
  "category": "weapon",
  "displayName": "Iron Sword",
  "hytaleItemId": "Weapon_Sword_Iron",
  "baseStat": 5,
  "rarity": "common",
  "description": "A reliable iron blade."
}
```

- `id`: Unique string identifier used internally
- `category`: One of `weapon`, `armor`, `crossbow`
- `displayName`: Player-facing name
- `hytaleItemId`: Maps to a Hytale asset ID for rendering/inventory
- `baseStat`: The base value of the item's primary stat (before scaling)
- `rarity`: One of `common`, `uncommon`, `rare`, `epic`
- `description`: Short flavor text (optional for MVP)

### Rarity Tiers

| Rarity | Multiplier | Drop Weight |
|--------|-----------|-------------|
| Common | 1.0x | 45% |
| Uncommon | 1.3x | 28% |
| Rare | 1.6x | 15% |
| Epic | 2.0x | 8% |
| Legendary | 2.5x | 4% |

### MVP Item List

**Weapons (10):**

| ID | Display Name | Hytale Asset | Base Stat |
|----|-------------|-------------|-----------|
| wood_sword | Wood Sword | Weapon_Sword_Wood | 3 |
| trork_sword | Trork Stone Sword | Weapon_Sword_Stone_Trork | 5 |
| iron_sword | Iron Sword | Weapon_Sword_Iron | 8 |
| mithril_sword | Mithril Sword | Weapon_Sword_Mithril | 12 |
| copper_axe | Copper Axe | Weapon_Axe_Copper | 4 |
| trork_axe | Trork Stone Axe | Weapon_Axe_Stone_Trork | 6 |
| iron_axe | Iron Axe | Weapon_Axe_Iron | 10 |
| adamantite_axe | Adamantite Axe | Weapon_Axe_Adamantite | 14 |
| daggers | Iron Daggers | Weapon_Daggers_Iron | 6 |
| mace | Mace | Weapon_Mace_Iron | 11 |

**Armor (5):**

| ID | Display Name | Hytale Asset | Base Stat |
|----|-------------|-------------|-----------|
| leather_armor | Leather Armor | Armor_Leather_Light_Chest | 2 |
| bronze_armor | Bronze Armor | Armor_Bronze_Chest | 4 |
| iron_armor | Iron Armor | Armor_Iron_Chest | 6 |
| adamantite_armor | Adamantite Armor | Armor_Adamantite_Chest | 9 |
| cobalt_armor | Cobalt Armor | Armor_Cobalt_Chest | 5 |

**Crossbows (5):**

| ID | Display Name | Hytale Asset | Base Stat |
|----|-------------|-------------|-----------|
| ancient_steel_crossbow | Ancient Steel Crossbow | Weapon_Crossbow_Ancient_Steel | 6 |
| iron_crossbow | Iron Crossbow | Weapon_Crossbow_Iron | 9 |

**17 items total** (10 weapons, 5 armor, 2 crossbows).

### Default Item (Fists)

When the player has no weapon equipped, a hardcoded fallback is used:
- `id`: `fists`, `category`: `weapon`, `baseStat`: 1, `rarity`: `common`
- Not included in the JSON config — this is a code constant, not a data entry
- Cannot appear in loot drops or upgrade offers

### Loading

- Items are loaded from `config/items.json` at plugin startup
- The database is **immutable after load** — no runtime modifications, thread-safe
  for reads from any context (world thread, tick thread, UI thread)
- If the config file is missing or malformed, the plugin logs an error and falls
  back to a hardcoded minimal set (1 weapon, 1 armor, 1 crossbow)

### Item Lookup API

```java
ItemDatabase.get(String id) -> ItemDefinition
ItemDatabase.getByCategory(Category category) -> List<ItemDefinition>
ItemDatabase.getByRarity(Rarity rarity) -> List<ItemDefinition>
ItemDatabase.getRandomForCategory(Category category, Rarity rarity) -> ItemDefinition
```

---

## 4. Formulas

### Effective Stat Calculation

```
effectiveStat = baseStat * rarityMultiplier * levelScaling
```

Where:
- `baseStat`: from item definition (integer, range 2-14)
- `rarityMultiplier`: Common=1.0, Uncommon=1.3, Rare=1.6, Epic=2.0
- `levelScaling = 1.0 + (LEVEL_SCALE_FACTOR * playerLevel)`
- `LEVEL_SCALE_FACTOR = 0.1` (tuning knob)

**Example:** Iron Sword (baseStat=8), Rare (1.6x), player level 5:
```
effectiveStat = 8 * 1.6 * (1.0 + 0.1 * 5) = 8 * 1.6 * 1.5 = 19.2 → 19
```

Result is floored to integer.

### Drop Weight Selection

When selecting a random item for a category:
1. Sum all drop weights for items in that category
2. Roll a random number in [0, totalWeight)
3. Walk the list, subtracting each weight until the roll is exhausted

This is the standard weighted random selection algorithm.

---

## 5. Edge Cases

- **Player has no weapon equipped**: Default to "Fists" — a virtual item with
  baseStat=1, common rarity, no hytaleItemId (uses empty hand)
- **Item ID not found in database**: Log a warning, return the default item for
  that category (first item in the list)
- **Config file missing**: Log error, load hardcoded fallback set
- **Config file has duplicate IDs**: Last definition wins, log a warning
- **Rarity field invalid or missing**: Default to Common
- **baseStat is 0 or negative**: Clamp to minimum of 1

---

## 6. Dependencies

- **Depended on by**: Combat System (reads damage), Floor Upgrade Selection (pulls
  items to offer), Level Scaling (applies scaling formula), Player Data (stores
  equipped item IDs), Inventory UI (displays items)
- **Depends on**: Nothing — this is a foundation system
- **Hytale API dependency**: `ItemStack` class for creating in-game item instances
  from `hytaleItemId`
- **Open dependency**: The damage reduction formula (flat subtraction vs. percentage)
  must be defined in the Combat System or Health & Lives GDD. This affects whether
  high-level armor can fully negate damage.
- **Asset verification required**: All `hytaleItemId` values are assumed and must be
  verified against Hytale's actual asset registry before implementation

---

## 7. Tuning Knobs

| Knob | Default | Safe Range | Affects |
|------|---------|-----------|---------|
| `LEVEL_SCALE_FACTOR` | 0.1 | 0.05 - 0.2 | How fast items scale with level. Too high = early items feel weak. |
| Rarity multipliers | 1.0/1.3/1.6/2.0 | 1.0 - 3.0 | Gap between rarity tiers. Too high = common items feel useless. |
| Drop weights | 50/30/15/5 | Positive integers | Rarity distribution. Must sum to 100 for readability. |
| Per-item `baseStat` | See table | 1 - 30 | Individual item power. Higher = category feels stronger. |

---

## 8. Acceptance Criteria

- [ ] Items load from `config/items.json` at startup without errors
- [ ] `ItemDatabase.get("iron_sword")` returns the correct definition
- [ ] `ItemDatabase.getByCategory(WEAPON)` returns exactly 10 items
- [ ] `ItemDatabase.getRandomForCategory(WEAPON, RARE)` returns only rare weapons
- [ ] Effective stat formula produces correct values (test: iron sword, rare, level 5 = 19)
- [ ] Missing config file falls back to hardcoded defaults without crashing
- [ ] Duplicate IDs in config produce a warning log
- [ ] All 20 MVP items are defined and loadable
- [ ] `hytaleItemId` values map to valid Hytale assets (manual verification)
