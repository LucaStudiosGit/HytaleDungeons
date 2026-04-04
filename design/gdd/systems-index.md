# Systems Index: Hytale Dungeons

> **Status**: Draft
> **Created**: 2026-04-01
> **Last Updated**: 2026-04-01
> **Source Concept**: design/gdd/game-concept.md

---

## Overview

Hytale Dungeons is a small-scope roguelike dungeon crawler mod. The mechanical
scope is intentionally tight: hack-and-slash combat, procedural floor generation,
a 3-life system, and a "pick 1 of 3 upgrades per floor" progression loop. There
is no narrative, no crafting, no networking (v1), and no sandbox building. Every
system listed here serves the core loop: enter floor, fight mobs, choose upgrade,
descend deeper.

---

## Systems Enumeration

| # | System Name | Category | Priority | Status | Design Doc | Depends On |
|---|-------------|----------|----------|--------|------------|------------|
| 1 | Player Controller | Core | MVP | Approved | design/gdd/player-controller.md | — |
| 2 | Camera System | Core | MVP | Approved | design/gdd/camera-system.md | Player Controller |
| 3 | Player Restrictions | Core | MVP | Approved | design/gdd/player-restrictions.md | Player Controller |
| 4 | Loot & Item Database | Economy | MVP | Implemented | design/gdd/loot-item-database.md | — |
| 5 | Run State Machine | Core | MVP | Approved | design/gdd/run-state-machine.md | Player Controller |
| 6 | Health & Lives | Gameplay | MVP | Approved | design/gdd/health-and-lives.md | Player Controller, Run State Machine |
| 7 | Combat System | Gameplay | MVP | Approved | design/gdd/combat-system.md | Player Controller, Health & Lives, Loot & Item Database |
| 8 | Player Data | Persistence | MVP | Approved | design/gdd/player-data.md | Loot & Item Database |
| 9 | Floor Generation | Gameplay | MVP | Approved | design/gdd/floor-generation.md | Run State Machine |
| 10 | Enemy AI | Gameplay | MVP | Not Started | — | Combat System, Health & Lives, Floor Generation |
| 11 | Level Scaling | Economy | MVP | Not Started | — | Loot & Item Database, Player Data, Enemy AI |
| 12 | Difficulty Scaling | Gameplay | MVP | Not Started | — | Run State Machine, Enemy AI, Floor Generation |
| 13 | Floor Upgrade Selection | Economy | MVP | Not Started | — | Run State Machine, Loot & Item Database, Level Scaling |
| 14 | HUD | UI | MVP | Not Started | — | Health & Lives, Run State Machine |
| 15 | Inventory UI | UI | MVP | Partial | — | Loot & Item Database, Player Data |
| 16 | Upgrade Selection UI | UI | MVP | Not Started | — | Floor Upgrade Selection |

---

## Categories

| Category | Description |
|----------|-------------|
| **Core** | Foundation systems everything depends on |
| **Gameplay** | The systems that make the game fun |
| **Economy** | Items, loot, scaling, and upgrade mechanics |
| **Persistence** | Save state and player data across runs |
| **UI** | Player-facing information displays |

---

## Priority Tiers

| Tier | Definition | Target Milestone |
|------|------------|------------------|
| **MVP** | Required for the core loop to function. All 16 systems are MVP — the concept is already scoped tight. | First playable prototype |

---

## Dependency Map

### Foundation Layer (no dependencies)

1. **Player Controller** — everything needs a player to control (partial implementation exists)
2. **Camera System** — renders the top-down view (partial implementation exists)
3. **Player Restrictions** — locks out default Hytale behaviors (partial implementation exists)
4. **Loot & Item Database** — defines all gear before anything can reference it

### Core Layer (depends on foundation)

5. **Run State Machine** — depends on: Player Controller
6. **Health & Lives** — depends on: Player Controller, Run State Machine
7. **Combat System** — depends on: Player Controller, Health & Lives, Loot & Item Database
8. **Player Data** — depends on: Loot & Item Database

### Feature Layer (depends on core)

9. **Floor Generation** — depends on: Run State Machine
10. **Enemy AI** — depends on: Combat System, Health & Lives, Floor Generation
11. **Level Scaling** — depends on: Loot & Item Database, Player Data, Enemy AI
12. **Difficulty Scaling** — depends on: Run State Machine, Enemy AI, Floor Generation
13. **Floor Upgrade Selection** — depends on: Run State Machine, Loot & Item Database, Level Scaling

### Presentation Layer (depends on features)

14. **HUD** — depends on: Health & Lives, Run State Machine
15. **Inventory UI** — depends on: Loot & Item Database, Player Data (partial implementation exists)
16. **Upgrade Selection UI** — depends on: Floor Upgrade Selection

---

## Recommended Design Order

| Order | System | Priority | Layer | Est. Effort |
|-------|--------|----------|-------|-------------|
| 1 | Loot & Item Database | MVP | Foundation | M |
| 2 | Player Controller | MVP | Foundation | S |
| 3 | Camera System | MVP | Foundation | S |
| 4 | Player Restrictions | MVP | Foundation | S |
| 5 | Run State Machine | MVP | Core | M |
| 6 | Health & Lives | MVP | Core | M |
| 7 | Combat System | MVP | Core | M |
| 8 | Player Data | MVP | Core | S |
| 9 | Floor Generation | MVP | Feature | L |
| 10 | Enemy AI | MVP | Feature | M |
| 11 | Level Scaling | MVP | Feature | M |
| 12 | Difficulty Scaling | MVP | Feature | S |
| 13 | Floor Upgrade Selection | MVP | Feature | S |
| 14 | HUD | MVP | Presentation | S |
| 15 | Inventory UI | MVP | Presentation | S |
| 16 | Upgrade Selection UI | MVP | Presentation | S |

Effort estimates: S = 1 session, M = 2-3 sessions, L = 4+ sessions.

---

## Circular Dependencies

- None found. All dependencies flow cleanly from Foundation -> Core -> Feature -> Presentation.

---

## High-Risk Systems

| System | Risk Type | Risk Description | Mitigation |
|--------|-----------|-----------------|------------|
| Floor Generation | Technical | Hytale API support for procedural world/structure generation is unproven | Prototype first — test what the API allows |
| Combat System | Technical | Hit feedback, knockback, and combat juice depend on API capabilities | Prototype combat feel early |
| Enemy AI | Technical | AI quality depends on what Hytale's modding API exposes for mob behavior | Prototype basic mob AI to assess API limits |

---

## Progress Tracker

| Metric | Count |
|--------|-------|
| Total systems identified | 16 |
| Partial implementations | 4 |
| Design docs started | 9 |
| Design docs reviewed | 9 |
| Design docs approved | 9 |
| MVP systems designed | 0/16 |

---

## Next Steps

- [ ] Design MVP-tier systems in order (use `/design-system [system-name]`)
- [ ] Run `/design-review` on each completed GDD
- [ ] Prototype the 3 high-risk systems early (`/prototype`)
- [ ] Run `/gate-check pre-production` when MVP systems are designed
- [ ] Plan first implementation sprint (`/sprint-plan new`)
