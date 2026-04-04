# Game Concept: Hytale Dungeons

*Created: 2026-04-01*
*Status: Draft*

---

## Elevator Pitch

> It's a roguelike dungeon crawler where you descend through procedurally
> generated floors, hack through mobs, and choose one gear upgrade per floor —
> all inside Hytale. Minecraft Dungeons distilled to its purest, most
> replayable form.

---

## Core Identity

| Aspect | Detail |
| ---- | ---- |
| **Genre** | Action roguelike dungeon crawler |
| **Platform** | PC (Hytale mod) |
| **Target Audience** | Hytale players who enjoy fast action games |
| **Player Count** | Single-player (co-op planned for v2) |
| **Session Length** | 15-30 minutes per run |
| **Monetization** | Free mod |
| **Estimated Scope** | Small (1-3 months) |
| **Comparable Titles** | Minecraft Dungeons, Hades, Vampire Survivors |

---

## Core Fantasy

You are a dungeon diver descending into an endless, ever-changing dungeon.
Each floor is more dangerous than the last. Your only advantages: quick
reflexes, smart gear choices, and three lives. How deep can you go?

The fantasy is the "one more floor" compulsion — the thrill of pushing your
luck deeper into danger with gear you've earned floor by floor.

---

## Unique Hook

"It's Minecraft Dungeons, AND ALSO it's a single endless procedural dungeon
where every floor forces a meaningful gear choice — weapon, armor, or bow."

The constraint of one upgrade per floor combined with roguelike permadeath
(3 lives) creates tension that the original Minecraft Dungeons never had.

---

## Player Experience Analysis (MDA Framework)

### Target Aesthetics (What the player FEELS)

| Aesthetic | Priority | How We Deliver It |
| ---- | ---- | ---- |
| **Sensation** (sensory pleasure) | 3 | Satisfying hack-and-slash combat feel, mob-clearing feedback |
| **Fantasy** (make-believe, role-playing) | 5 | Dungeon diver power fantasy |
| **Narrative** (drama, story arc) | N/A | No story — pure gameplay |
| **Challenge** (obstacle course, mastery) | 1 | Escalating floor difficulty, 3-life pressure, gear decisions |
| **Fellowship** (social connection) | N/A | Deferred to co-op v2 |
| **Discovery** (exploration, secrets) | 2 | Procedural floors, new loot in the pool, deeper = new enemy types |
| **Expression** (self-expression, creativity) | 6 | Gear loadout choices define playstyle |
| **Submission** (relaxation, comfort zone) | 4 | Easy to pick up, fast to start, flow-state hack-and-slash |

### Key Dynamics (Emergent player behaviors)

- Players push "just one more floor" until they die or choose to stop
- Players develop preferences for weapon vs. armor vs. bow upgrades based on playstyle
- Risk/reward tension increases each floor — do I fight that mob pack or try to sneak past?
- Players learn enemy patterns across runs, improving their floor depth over time

### Core Mechanics (Systems we build)

1. **Hack-and-slash combat** — fast, button-mashy melee and ranged attacks
2. **Procedural floor generation** — each floor is a new layout with rooms and corridors
3. **Floor-end upgrade selection** — choose 1 of 3 upgrades (weapon/armor/bow) after each floor
4. **3-life system** — die = restart current floor with gear, 3 deaths = run over
5. **Level-scaled loot and enemies** — gear and mob difficulty adjust to player level

---

## Player Motivation Profile

### Primary Psychological Needs Served

| Need | How This Game Satisfies It | Strength |
| ---- | ---- | ---- |
| **Autonomy** (freedom, meaningful choice) | Gear upgrade choices each floor, playstyle freedom | Supporting |
| **Competence** (mastery, skill growth) | Deeper floors = proof of skill, learning enemy patterns | Core |
| **Relatedness** (connection, belonging) | Deferred to co-op v2 | Minimal |

### Player Type Appeal (Bartle Taxonomy)

- [x] **Achievers** (goal completion, collection, progression) — How deep can I get? What's my best run?
- [x] **Explorers** (discovery, understanding systems, finding secrets) — Procedural floors, new enemy types deeper down
- [ ] **Socializers** (relationships, cooperation, community) — Deferred to co-op v2
- [ ] **Killers/Competitors** (domination, PvP, leaderboards) — Potential for depth leaderboards later

### Flow State Design

- **Onboarding curve**: Floor 1-2 are easy — teaches combat basics through play, no tutorials
- **Difficulty scaling**: Each floor adds more mobs, tougher variants, and introduces new enemy types
- **Feedback clarity**: Floor number = progress. Gear upgrades are visible and immediate. Death counter is always visible.
- **Recovery from failure**: Instant — die, restart same floor. Run over? Back to floor 1 in seconds.

---

## Core Loop

### Moment-to-Moment (30 seconds)
Enter a room, hack through mobs, collect drops. Combat is fast, mashy, and
satisfying. Swing weapon at close range, switch to bow for distant targets,
take hits absorbed by armor. Room clear = move forward.

### Short-Term (5-15 minutes)
Clear a floor of rooms and enemies. Reach the floor exit. Choose 1 of 3
gear upgrades (weapon, armor, or bow). Descend to the next floor. Each floor
is harder — more mobs, tougher variants, new mechanics.

### Session-Level (15-30 minutes)
A complete run: start at floor 1, descend as deep as possible. A run ends
when all 3 lives are spent or the player extracts. Natural stopping point
is death. Reason to return: "I can get deeper next time."

### Long-Term Progression
Player level persists across runs. Gear and enemies scale to level. Details
TBD — potential unlocks for the loot pool, cosmetics, or new dungeon themes.

### Retention Hooks
- **Curiosity**: What's on the next floor? What enemies appear deeper?
- **Investment**: Player level persists, improving over time
- **Social**: Deferred to co-op v2
- **Mastery**: Beating your personal best floor depth, learning enemy patterns

---

## Game Pillars

### Pillar 1: Instant Action
Zero friction from "I want to play" to fighting mobs. No menus, no setup,
no tutorials. Drop in, start swinging.

*Design test*: "Does this feature add time before the player is fighting? Cut it."

### Pillar 2: Simple Choices, Big Impact
Three gear slots. One upgrade per floor. Every choice is meaningful because
there are so few.

*Design test*: "Are we adding complexity or depth? Complexity = cut. Depth = keep."

### Pillar 3: One More Floor
Every floor should end with the player thinking "I can do one more."
Escalation must feel exciting, not punishing.

*Design test*: "Does this make the player want to push deeper or quit? If quit, redesign."

### Anti-Pillars (What This Game Is NOT)

- **NOT an inventory management game**: There's a small inventory for collecting loot, but only 3 equipped slots. The game never asks you to spend time sorting or optimizing.
- **NOT a story game**: No cutscenes, no dialogue, no lore dumps. Pure gameplay.
- **NOT a build/sandbox experience**: This is Hytale's engine, but players don't build. They fight.

---

## Inspiration and References

| Reference | What We Take From It | What We Do Differently | Why It Matters |
| ---- | ---- | ---- | ---- |
| Minecraft Dungeons | Fast hack-and-slash combat, simple 3-slot gear, accessible design | Roguelike structure instead of fixed levels, procedural floors | Proves the combat formula works and is fun |
| Minecraft Dungeons Tower | Floor-by-floor progression, choose-one-upgrade-per-floor | Core game mode, not a side mode. 3-life system adds stakes | Validates the "ascending/descending tower" loop |
| Hades | Roguelike structure, satisfying combat feel, "one more run" | Simpler — no narrative layer, fewer systems, Hytale setting | Proves roguelike + action combat = compelling |

**Non-game inspirations**: The simplicity of classic arcade games — easy to understand, hard to master, always pulling you back for one more try.

---

## Target Player Profile

| Attribute | Detail |
| ---- | ---- |
| **Age range** | 12-25 |
| **Gaming experience** | Casual to mid-core |
| **Time availability** | 15-30 minute sessions |
| **Platform preference** | PC (Hytale) |
| **Current games they play** | Hytale, Minecraft, Minecraft Dungeons, Roblox dungeon games |
| **What they're looking for** | Fast action gameplay inside Hytale, something different from building/survival |
| **What would turn them away** | Complexity, long setup times, inventory management, steep difficulty |

---

## Technical Considerations

| Consideration | Assessment |
| ---- | ---- |
| **Engine** | Hytale modding API (Java) — already in development |
| **Key Technical Challenges** | Procedural floor generation within Hytale's mod API; combat feel/juice with available tools; enemy AI within mod constraints |
| **Art Style** | Hytale's native 3D voxel style — no custom art needed |
| **Art Pipeline Complexity** | Low — leverage Hytale's existing assets |
| **Audio Needs** | Minimal — use Hytale's existing SFX, potentially custom music |
| **Networking** | None for v1 (single-player); client-server for co-op v2 |
| **Content Volume** | 10-15 floor templates, 3-5 enemy types, ~20 gear items (10 weapons, 5 armors, 5 bows) |
| **Procedural Systems** | Floor layout generation from room templates |

---

## Risks and Open Questions

### Design Risks
- Core loop may feel repetitive without enough floor variety or enemy diversity
- 3-life system may feel too punishing or too lenient — needs playtesting

### Technical Risks
- Procedural floor generation may be limited by Hytale's mod API capabilities
- Combat feel (hit feedback, knockback, screen shake) may be hard to achieve in a mod
- Enemy AI quality depends on what Hytale's API exposes

### Market Risks
- Hytale's modding community size is unproven — audience may be small
- Competing with the actual Minecraft Dungeons for the same player fantasy

### Scope Risks
- Procedural generation could consume disproportionate development time
- "Small scope" can creep if loot tables and enemy types expand

### Open Questions
- What does Hytale's mod API support for world/structure generation? — Prototype floor gen first
- Can we get satisfying combat feel (knockback, hit feedback) through the API? — Prototype combat juice
- How does the floor-end upgrade UI work within Hytale's UI framework? — Prototype the selection screen

---

## MVP Definition

**Core hypothesis**: "Players find the descend-fight-upgrade loop engaging
enough to attempt multiple 15-minute runs."

**Required for MVP**:
1. Procedural floor generation (even simple room-corridor-room)
2. Hack-and-slash combat with 1 weapon type, 1 armor, 1 bow
3. Floor-end upgrade selection (choose 1 of 3)
4. 3-life system with floor restart on death
5. 3-5 enemy types with level scaling
6. 10 floors of escalating difficulty

**Explicitly NOT in MVP** (defer to later):
- Co-op multiplayer
- Persistent progression / unlocks
- Boss fights
- Traps / environmental hazards
- Multiple dungeon themes
- Leaderboards

### Scope Tiers

| Tier | Content | Features | Timeline |
| ---- | ---- | ---- | ---- |
| **MVP** | 10 floors, 3-5 enemy types, ~10 gear items | Core loop: combat, floors, upgrades, 3 lives | 3-4 weeks |
| **Vertical Slice** | 15 floors, boss every 5th floor, traps | Core + boss fights + environmental hazards | 6-8 weeks |
| **Alpha** | 20+ floors, multiple themes, 10+ enemy types | All features, co-op prototype | 10-12 weeks |
| **Full Vision** | Endless floors, full co-op, progression system | Complete game, polished | 3+ months |

---

## Next Steps

- [ ] Configure project for Java/Hytale (`/setup-engine` — adapt for Hytale mod)
- [ ] Validate concept doc (`/design-review design/gdd/game-concept.md`)
- [ ] Decompose concept into systems (`/map-systems`)
- [ ] Author per-system GDDs (`/design-system` for combat, loot, floor gen, etc.)
- [ ] Prototype floor generation (`/prototype floor-gen`)
- [ ] Prototype combat feel (`/prototype combat`)
- [ ] Playtest the prototype (`/playtest-report`)
- [ ] Plan first sprint (`/sprint-plan new`)
