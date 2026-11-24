# AGENTS.md: libs/world-gen

Generated area layouts (package `com.pessimisticit.scarcity.world.gen`;
epic #234, #310). Contract: `docs/resources.md` "Shapes" and "Walls", and
`docs/decisions.md` 2026-11-23: walls are `stone-wall` fixed entities with
convex polygons, and the tessellation isn't stored. Read root `AGENTS.md`
"Randomness" too.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope. The enforcer rule `pure-world-gen` in `pom.xml` bans Spring,
JDBC and `resource-model`. Tests need no Docker. Lint applies with no
baseline entries.

**What it emits.** Entities and points: wall entities without ids, the
stairs' positions, spawn points, and every draw. Never a navigation graph:
pathfinding (#237, `libs/pathfinding`) builds its own from the walls'
shapes. The open cells are in the `Layout` for tests and placement only.
Nothing stores them.

**Every random choice is a named draw** through `Recorder`, in order, with
its distribution and parameters. A choice among `n` is a uniform over
`[0, n)`, floored, not a categorical with `n` weights. Never call the
`RandomGenerator` directly.

**One wall per cell, never bigger** (`docs/decisions.md` 2026-11-24). A
wall that spans many cells tells the player how far the rock goes, so no
tessellation merges them. Rock that touches no open cell has no wall.

**Generators see only `CellGraph`.** A new tessellation is one class that
implements it; `walls(open)` comes with it, so long as neighbouring cells
compute each shared vertex once and hold equal positions. A new generator is one class over
`CellGraph`. Border cells stay solid, so the walls always enclose the open
space.

## API
| type | what |
|---|---|
| `CellGraph` | cells `0..size()-1`: `polygon` (convex, counter-clockwise), `centroid`, `neighbours` (they share an edge), `onBorder`, `minEdge`, `bounds`, and `walls(open)`, a default: one `stone-wall` per solid cell that touches an open cell (shares a vertex, compared exactly), its polygon the cell's, so they enclose the open space with no gap |
| `SquareGrid(cols, rows, size)` | squares from (0, 0), with cell `row * cols + col` and neighbours east, north, west, south. Its walls are `CellGraph`'s default |
| `Recorder(rng)` | `uniform`, `index` and `bernoulli`, each by name. `draws()` returns them in order as `Named(name, Draw)` |
| `Connectivity` | `reachable(graph, open, start)`. `connect(graph, open, start)` carves the shortest passage through rock (never a border cell) from each unreached part and returns how many cells it opened |
| `RoomsAndCorridors(settings).generate(graph, recorder)` | draws a room count (`rooms`), then each room's box (`room[i].try[t].width/height/x/y`), drawn again if it would touch another room. Corridors are one cell wide, in an L (`corridor[i].bendFirstAlongX`). Then it connects. Returns `Plan(open, rooms)`; each `Room` has its cells and a `centre` cell, all of whose neighbours are in the room |
| `WorldGen.cellSize(r)` | `max(0.8, 2r / 0.75)` m: a one-cell passage fits an entity of radius `r` |
| `WorldGen.squares(width, height, passerRadius, settings, rng)` | a `Layout(walls, openCells, up, down, spawns, draws)`. The stairs up are at the first room's centre. The stairs down are at the centre of the room farthest from it through open cells. Every other room cell's centroid is a spawn |

Default settings (`Settings.DEFAULT`): 4 to 8 rooms, sides in [2.4, 5.6) m,
20 tries per room. A 24 × 18 m level with 0.8 m cells had 80 to 217 walls
over 200 seeds, 26 to 71 KB of JSON in all (#186: a level is made in one
batch, which subscribers reload). The tests hold it under 400.
