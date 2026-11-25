# AGENTS.md: libs/world-gen

Generated area layouts (package `com.pessimisticit.scarcity.world.gen`;
epic #234, #310). Contract: `docs/resources.md` "Shapes" and "Walls", and
`docs/decisions.md` 2026-11-23: walls are `wall` fixed entities with
convex polygons, and the tessellation isn't stored. Read root `AGENTS.md`
"Randomness" too. **Changing (#349):** from 2026-11-25 the cells are stored
and walls name them (`docs/resources.md` "Cells"); #352 brings this module
in line.

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
| `VoronoiGraph(width, height, sites, relaxations, recorder)` | a Voronoi tessellation of the box from (0, 0) (#312): `sites` points drawn uniformly (`voronoi.site[i].x`, `.y`), then `relaxations` rounds of Lloyd relaxation (few: chaotic, many: even). Convex cells clipped to the box by half-plane clipping, no geometry library. Vertices within `VertexSnap.TOLERANCE` (2 cm) are one `Position`, shared by every cell that has it, so `walls(open)` leaves no hole (a vertex that move leaves reflex in a cell is dropped from that cell; a cell with fewer than 3 vertices is dropped, so cell numbers are those of the cells that remain). `neighbours` share an edge; border cells have an edge no cell shares; `minEdge` is the shortest shared edge (about the tolerance: edges shorter than it are merged away) |
| `Recorder(rng)` | `uniform`, `index`, `categorical` (by weights) and `bernoulli`, each by name. `draws()` returns them in order as `Named(name, Draw)` |
| `MaterialDraws` | `wall(recorder)`: a level's wall material, `level.material`, a categorical over `Materials.all()` by `weight` (docs/resources.md, "Materials"). A game's patterns come from its seed (`MaterialPatterns`, world-model) |
| `Layout.walls(material)` | the layout's wall outlines (`wall` entities with no material or mass) made of `material`: `Material.wall`, mass density × area × 3 m |
| `Connectivity` | `reachable(graph, open, start)`. `connect(graph, open, start)` carves the shortest passage through rock (never a border cell) from each unreached part and returns how many cells it opened |
| `RoomsAndCorridors(settings).generate(graph, recorder)` | draws a room count (`rooms`), then each room's box (`room[i].try[t].width/height/x/y`), drawn again if it would touch another room. Corridors are one cell wide, in an L (`corridor[i].bendFirstAlongX`). Then it connects. Returns `Plan(open, rooms)`; each `Room` has its cells and a `centre` cell, all of whose neighbours are in the room |
| `CaveGenerator(settings).generate(graph, recorder, need)` | caves on any `CellGraph` (#312): each non-border cell is drawn open with a `bernoulli` (`cave[t].cell[c].open`, p = `fill`, attempt `t`), then `smoothing` rounds (open when more than half of itself and its neighbours are), regions under `minRegion` cells filled, the rest joined by `Connectivity.connect`, then `Passages` makes the cells a disc of radius `need` can reach (`Navigation`: open cells whose centroid is `need` or more from every wall, joined when the line between centroids is too) one region, widening a joining passage (opening the cells touching it) and filling in a region that border rock stops it joining. Stairs go on cells of that region more than 0.6 m from every wall: up drawn (`cave.stairsUp`), down the farthest from it. Spawns: other open cells over 0.4 m from every wall and 1 m from either stairs. Returns `Cave(open, up, down, spawns)`. `Settings.DEFAULT`: fill 0.5, 4 rounds, minRegion 12; a cave a disc can reach fewer than `minRoomy` (40) cells of is drawn again, up to 10 attempts |
| `WorldGen.cellSize(r)` | `max(0.8, 2r / 0.75)` m: a one-cell passage fits an entity of radius `r` |
| `WorldGen.squares(width, height, passerRadius, settings, rng)` | a `Layout(walls, openCells, up, down, spawns, draws)`. The stairs up are at the first room's centre. The stairs down are at the centre of the room farthest from it through open cells. Every other room cell's centroid is a spawn |
| `WorldGen.sites(width, height, r)` | `width * height / cellSize(r)^2`, rounded: a mean cell area near `cellSize^2` |
| `WorldGen.voronoi(width, height, passerRadius, settings, rng)` | a `Layout` (#312): `VoronoiGraph` with `sites(...)` sites and `settings.relaxations()` rounds (`VoronoiSettings.DEFAULT`: 3 rounds, `CaveGenerator.Settings.DEFAULT`), then caves for `need = passerRadius + MARGIN` (0.1 m), so a disc that far from every wall can go from the stairs up to the stairs down |

**Voronoi passages.** The cells of a Voronoi tessellation aren't `cellSize` wide
(a hexagon of that area is 0.87 `cellSize` flat to flat and shared edges are
0.62 `cellSize`), so "a one-cell passage fits" doesn't hold and `minEdge` says
nothing about it. The test is a disc of radius `r + 0.1` following centroids
(`Navigation`). `libs/pathfinding`'s `Pathfinder` found no path in 16 of 20 levels
when passages were only checked at the gap between rock at the two ends of a shared edge.
A grid of points near the open space can't test that walls enclose it (as for squares): rock
behind a tiny edge between two walls is near an open cell and touches it at a point only. The
tests look just past each open cell's edges and round each of its corners instead.

Default settings (`Settings.DEFAULT`): 4 to 8 rooms, sides in [2.4, 5.6) m,
20 tries per room. A 24 × 18 m level with 0.8 m cells had 80 to 217 walls
over 200 seeds, 26 to 71 KB of JSON in all (#186: a level is made in one
batch, which subscribers reload). The tests hold it under 400.

A Voronoi level (`WorldGen.voronoi`, 24 x 18 m, a 0.3 m passer: 675 sites, 3
relaxations) had 45 to 177 walls over 200 seeds, 23 to 89 KB of JSON in all, and
at least 35 spawns (#186: more than squares, whose levels were 26 to 71 KB). The
tests hold it between 30 and 250. `Pathfinder.path` from the stairs up to the
stairs down took up to 0.5 s warm over 20 seeds, against 32 ms for squares (the
visibility graph grows with the square of the walls corners; #237 may want a
faster search).
