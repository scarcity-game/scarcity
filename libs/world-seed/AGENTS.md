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
| `WorldIds` | `GAME`, `AREA`, `PILLAR`, `ORBITER`, `MOUSE_1`..`MOUSE_3`, `DIVE_GAME`, `DIVE_AREA`, `DIVE_STAIRS_UP`, `DIVE_STAIRS_DOWN`, `DIVER`, `DIVE_MOUSE`, `WALLS` (the test room's four), `diveWall(i)` (depth 0's generated walls, in the generator's order). Fixed, part of the contract with clients |
| `TestArea` | the fresh typed states: game (its `materialPatterns` each material's first, `Materials.fallbackPatterns()`: it is hand-built and draws nothing), area (10x8, `time` 0.0), pillar (the `pillar` prototype at (5, 4): granite, its mass derived) and orbiter (`src/main/resources/seed/orbiter.json`), three mice (`TestArea.mouse(i)`, #287, at the prototype's values, not varied: seeds stay stable) |
| `DiveGame` | the dive game's fresh states (#172, #311): `game(seed)`, the game made from a `GameSeed` (its `materialPatterns` drawn from it); `level(rng)`, depth 0, a generated `Levels.Level`; the area with the diver up and the mouse pending; `diver(level)`, the diver (`diver`, `seed/diver.json`) on the stairs up |
| `Rooms` | `enclose(width, height[, thickness])`: four granite `wall` rectangles (0.5 m, each weighing its area of granite 3 m tall) just outside the room [0,W)x[0,H), overlapping at the corners (#296). The test room's walls; `WorldIds.WALLS` are their fixed ids |
| `Levels` | `make(depth, rng)`: a generated dive level (#311, #312), depth 0 included. The first draw, `level.strategy` (a uniform index over `Levels.STRATEGIES`, floored), picks one `Strategy`, a `Tessellation` and a `Generator` made with `WorldGen.make`, whose `type()` and `params` are the log's `tessellation` and `generator`: squares with rooms and corridors, a Voronoi tessellation with caves (`WorldGen.voronoi`) or an aperiodic Penrose rhomb tiling with caves (`WorldGen.penrose`); all are possible at every depth (choice by depth is #312's). The second, `level.material` (`MaterialDraws.wall`, a categorical by weight), is what its walls are made of. Then the level's shape (#395, `LevelShape`, every distribution a named constant there): `level.width` (uniform [20, 72) m), `level.height` (uniform [15, 54) m), `level.cellSide` (gaussian, mean 1.1, sd 0.3, truncated to [1, 1.7) times `WorldGen.cellSize(PASSER_RADIUS)`, the diver's 0.3 m, so never below it, drawn in metres) and one openness draw by strategy: `level.openness.fill` (caves, N(0.5, 0.1) truncated to [0.4, 0.6), the cave `fill`) or `level.openness.roomSides` (rooms, N(1, 0.2) truncated to [0.6, 1.3), a factor on the room sides 2.4 to 5.6 m, which stay at least 2 cells across). The bounds are where every seed of `LevelShapeTest`'s sweep and every corner of the ranges makes a level. Cave levels draw their `minRoomy` as 6% of the level's cells (at most 40) and rooms and caves are retried more often (100 and 40 attempts), since a small level has few cells. A `Level` carries its `width`, `height` and `cellSide`; the `tessellation` and `generator` params show the drawn values. A new level from a seed differs from before #395; stored levels are unchanged. The `tessellation` is `squares {cols, rows, size}` , `voronoi {width, height, sites, relaxations}` or `penrose {width, height, edge}`, the `generator` `rooms-and-corridors {...}` or `cave {fill, smoothing, minRegion, clearance}`. A `Level` has its `material`, the walls (no ids; `wall`s of that material, each weighing its area of it 3 m tall), the stairs up at the layout's up point (where a traveller arrives), the stairs down, the mouse at a drawn spawn (`mouse.spawn`), its `radius`, `mass` and `speed` varied around the prototype (`mouse.radius`, `mouse.mass`, `mouse.speed`, `Draws.vary`), the stairs' masses varied around their prototypes' (`stairs.up.mass`, `stairs.down.mass`), and how it was made: `tessellation` and `generator` (`{type, params}`) and every draw in order, for the area's first log entry. services/world's `Crossing` makes levels below with it |
| `Seeder(store, tx, rng, generated).seed()` | one transaction; seeds each game that is missing (test game, dive game); true if it seeded either. `rng` draws the dive game's seed (`GameSeed.draw(rng, build)`, `build` the server's git sha; the 4-argument constructor uses `unknown`), then depth 0; the pattern draws go to the debug log. For a dive game already stored, it logs a warning when its seed is from another build; `generated.made(areaId, level)` is called in the transaction once a generated area is created, so the caller begins its log (services/world `LevelLog`). Run it after Flyway |

## Seed
When `GAME` doesn't exist: game, area, pillar, orbiter, three mice created with
`ResourceStore.create`. Versions after: **game 2, area 10, the rest 1**
(creating a child bumps its parent); the area's children are pillar, orbiter,
three mice and four walls (#296). The mice (#287, `mouse` prototype, controller `microbe`, step/wait) are at (1,1), (9,1)
and (9,7), free to roam and to collide with the orbiter now and then (that tests collisions over time); they are `pending` from time 0 and
the orbiter, whose id sorts first, stays `activeEntity`. A newly seeded room therefore has them;
an already-seeded one does not (seeding skips an existing game, see "No upgrade path"). The orbiter has `controller {"kind":"service","principal":"orbital"}`,
no `behaviour` and `actions` step/wait/throw, at (8,4) with speed 0.5. No
log entry: the area's first advance starts the log with a snapshot.

When `GAME` exists nothing is written. Two instances seeding at once: the
loser's insert hits the primary key, and its `DuplicateKeyException` is
caught.

**The dive game** (#172) is seeded beside the test game, each only if its own game
is missing (so a deployed world gets it without the test game being touched). Its depth 0
area has children stairs up, stairs down, diver, mouse, then its generated walls; the diver is `activeEntity` and
the mouse `pending` (both idle since 0, ties break by id, so the diver's id sorts first).
Every level is generated (#311): depth 0 here, the ones below by `Crossing`, each from the
world's random source, and each area's log begins with a snapshot holding how it was made
(docs/log.md). The diver finds its way round the walls with `libs/pathfinding`. A mouse in the queue means going back to a level
needs the `microbe` service to act. Stairs up at depth 0 lead nowhere (yet).

**No upgrade path.** The tick loop's state (area `clock`, orbiter
`behaviour`) is not ported: the human chose to start the world clean
(2026-11-14). services/world's migration `V3__start_clean` empties the old
test area, so this seeds it afresh. If the shape changes again, decide then
whether to upgrade or start clean.

**`up` (#148, now `activeEntity`, #164) was not in the seed that V3 left in the cluster**, so services/world's
`V4__bootstrap_up` writes the turn queue into every area with no `up` that has never
advanced. `BootstrapUpMigrationTest` runs it on the old shape. Computing `up` at load for
any area is #158.

## Tests (Testcontainers)
`DbTest`: one Postgres with services/world's migrations (so `modules.yaml`
lists that directory in `paths`), tables truncated before each test (the
ids are fixed). `FreshSeedTest`: versions, children and states; seeding
again writes nothing; two instances at once seed once.
