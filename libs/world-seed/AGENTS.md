# AGENTS.md: libs/world-seed

The test area: its fixed ids, its fresh states and seeding it (package
`com.pessimisticit.scarcity.world.seed`). Contract: `docs/resources.md` and
`docs/actions.md` ("Game time", "Controllers"). Part of #103;
`services/world` wires it in #112 and deletes its own `Seeder`, `WorldIds`
and `seed/`.

Depends on `libs/resource-model` (the store) and `libs/world-model`
(states). No Spring beans: `Seeder` takes its collaborators in its
constructor. Lint applies with no baseline entries.

## API
| type | what |
|---|---|
| `WorldIds` | `GAME`, `AREA`, `PILLAR`, `ORBITER`. Fixed, part of the contract with clients |
| `TestArea` | the fresh typed states: game, area (10x8, `time` 0.0), pillar and orbiter (`src/main/resources/seed/*.json`) |
| `Seeder(store, tx).seed()` | one transaction; true if it seeded, false if the game existed. Run it after Flyway |

## Seed
When `GAME` doesn't exist: game, area, pillar, orbiter created with
`ResourceStore.create`. Versions after: **game 2, area 3, pillar 1,
orbiter 1** (creating a child bumps its parent); the area's children are
pillar, orbiter. The orbiter has `controller {"kind":"service","principal":"orbital"}`,
no `behaviour` and `actions` step/wait/throw, at (8,4) with speed 0.5. No
log entry: the area's first advance starts the log with a snapshot.

When `GAME` exists nothing is written. Two instances seeding at once: the
loser's insert hits the primary key, and its `DuplicateKeyException` is
caught.

**No upgrade path.** The tick loop's state (area `clock`, orbiter
`behaviour`) is not ported: the human chose to start the world clean
(2026-11-14). services/world's migration `V3__start_clean` empties the old
test area, so this seeds it afresh. If the shape changes again, decide then
whether to upgrade or start clean.

## Tests (Testcontainers)
`DbTest`: one Postgres with services/world's migrations (so `modules.yaml`
lists that directory in `paths`), tables truncated before each test (the
ids are fixed). `FreshSeedTest`: versions, children and states; seeding
again writes nothing; two instances at once seed once.
