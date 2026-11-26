# AGENTS.md: libs/world-gen

Generated area layouts (package `com.pessimisticit.scarcity.world.gen`;
epic #234, #310). Contract: `docs/resources.md` "Shapes" and "Walls", and
`docs/decisions.md` 2026-11-23 and 2026-11-25: walls are `wall` fixed
entities, and an area keeps its cells, which walls name (`docs/resources.md`
"Cells"). Read root `AGENTS.md` "Randomness" too.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope. The enforcer rule `pure-world-gen` in `pom.xml` bans Spring,
JDBC and `resource-model`. Tests need no Docker. Lint applies with no
baseline entries.

**What it emits.** The area's **cells** (every cell of the tessellation,
`floor`, `wall` or `rock`, with ids, neighbours, `clearance` and `width`:
`world-model`'s `CellMesh`), wall entities without ids, the stairs'
positions, spawn points, and every draw. Cell ids come from the same random
source after every named draw, and are not draws themselves, so a seed's
draws are what they were. The cells must tile with no T-junction: every
edge with no cell across it lies on the bounds (`LayoutCellsTest`, every
tessellation). For now walls come two ways: `Layout.walls(material)` (their
own polygons, what world-seed stores) and `Layout.cellWalls(material)`
(naming their cells); #354 moves the seed to the second and drops the first.

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
| `SquareGrid(cols, rows, size)` | squares from (0, 0), with cell `row * cols + col` and neighbours east, north, west, south (its own: `PolygonGraph`'s follow each polygon's edges, and levels depend on this order). `polygon`, `onBorder` and `bounds` come from a `PolygonGraph` of the squares, so the check below runs on them. Its walls are `CellGraph`'s default |
| `VoronoiGraph(width, height, sites, relaxations, recorder)` | a Voronoi tessellation of the box from (0, 0) (#312): `sites` points drawn uniformly (`voronoi.site[i].x`, `.y`), then `relaxations` rounds of Lloyd relaxation (few: chaotic, many: even). Convex cells clipped to the box by half-plane clipping, no geometry library. **Exact shared vertices** (`VoronoiVertices`, #352): each corner of a clipped cell remembers the two lines it lies on (a bisector with another site, or a side of the box); with the cell's site that is its key, and its position is computed once per key (a circumcentre, a bisector meeting a side, or a corner of the box), so every cell meeting there holds the same `Position` and every shared edge is the same in both cells. Positions within 1e-9 m (nearly cocircular sites) are one; a cell left with fewer than 3 vertices is dropped, so cell numbers are those of the cells that remain. A vertex on a side has that side's coordinate exactly. `neighbours` share an edge; border cells have an edge no cell shares; `minEdge` is the shortest shared edge, which can be well under a centimetre (a real short Voronoi edge) |
| `PolygonGraph(polygons, lower, upper)` | a `CellGraph` from convex counter-clockwise polygons that meet edge to edge at exactly equal `Position`s, in the box `lower`..`upper`: neighbours share an edge, a border cell has an edge no cell shares, `minEdge` is the shortest shared edge. What `VoronoiGraph` and `PenroseGraph` build on; a new tiling makes its polygons and hands them over. **The constructor checks the tiling**: every edge is held by exactly two cells, or by one and lies along a side of the box (the side's coordinate exactly); else `IllegalArgumentException` names the cell and the edge's endpoints (a T-junction, a gap, an edge held by three cells, an edge inside the box with no neighbour) |
| `BoxClip.toBox(polygon, width, height)` | Sutherland-Hodgman clipping of a convex polygon to the box from (0, 0), for a tiling whose cells cross it: a crossing is computed from the edge's lexicographically lower end, so both cells sharing an edge get the identical point; a side's coordinate is exact; a piece with no area is null. `PenroseGraph` uses it; `VoronoiCells` clips by half-planes on its own |
| `PenroseGraph(width, height, edge, recorder)` | an aperiodic Penrose rhomb tiling (P3) of the box from (0, 0), by de Bruijn's pentagrid: five families of lines at `rotation + 72° j` (`penrose.rotation`, uniform in [0, 72°)), offset by `penrose.offset[0..3]` (uniform in [0, 1)) and `offset[4]`, minus their sum. Each crossing of two lines is a thick (72°) or thin (36°) rhomb of side `edge`, about φ thick to one thin. **Exact shared vertices**: a vertex is `Σ K_j e_j` for five integers, its key, computed once per key. Rhombs that cross the box are clipped to it with `BoxClip`; slivers of no area are dropped |
| `Recorder(rng)` | `uniform`, `index`, `categorical` (by weights) and `bernoulli`, each by name. `draws()` returns them in order as `Named(name, Draw)`. `newCellId()` is a cell id from the same source, not a draw |
| `MaterialDraws` | `wall(recorder)`: a level's wall material, `level.material`, a categorical over `Materials.all()` by `weight` (docs/resources.md, "Materials"). A game's patterns come from its seed (`MaterialPatterns`, world-model) |
| `Layout.walls(material)` | the layout's wall outlines (`wall` entities with no material or mass) made of `material`: `Material.wall`, mass density × area × 3 m |
| `Layout.cells()`, `cellMesh()`, `cellWalls(material)` | the area's cells (floor where the plan is open; ids from `Recorder.newCellId`), them as a `CellMesh`, and one `Material.wall(cell)` per `wall` cell: the wall names its cell and has no polygon |
| `Connectivity` | `reachable(graph, open, start)`. `connect(graph, open, start)` carves the shortest passage through rock (never a border cell) from each unreached part and returns how many cells it opened; a part border cells wall in (a narrow arm of an `Outline`) is closed instead |
| `Tessellation` | cuts a `width` by `height` box into cells of mean area about `side²` (#386): `type()` and `params(width, height, side)` for the level's log, `make(width, height, side, recorder)`. `Squares()` (`cols`, `rows`, `size`), `Voronoi(relaxations)` (`DEFAULT` 3; `sites(width, height, side)` sites), `Penrose(edge)` (rhombs of side `edge * side`; `DEFAULT` is `MEAN_AREA_EDGE`, 1.11). A new tiling is one more of these |
| `Generator` | chooses the open cells, stairs and spawns on any `CellGraph` (#386): `type()`, `params(need)` for the log, `generate(graph, recorder, need)`, a `Plan(open, up, down, spawns)` where a disc of radius `need` can go from `up` to `down`. `PairsTest` holds every tessellation with every generator to that |
| `RoomsAndCorridors(settings).rooms(graph, recorder)` | draws a room count (`rooms`), then each room's box (`room[i].try[t].width/height/x/y`), drawn again if it would touch another room. Corridors are one cell wide, in an L (`corridor[i].bendFirstAlongX`). Then it connects. Returns `Rooms(open, rooms)`; each `Room` has its cells and a `centre` cell, all of whose neighbours are in the room |
| `RoomsAndCorridors(settings).generate(graph, recorder, need)` | the rooms as a `Plan`. A one-cell corridor fits when every cell is at least `2 need` wide (squares of `cellSize`), and then the plan is as drawn; on narrower cells (Voronoi, Penrose) the corridors are widened by `Passages` where the disc can't pass, and rooms that cuts off are left out. The stairs up are at the first room's centre, the stairs down at the centre of the room farthest from it through open cells; every other room cell is a spawn |
| `WorldGen.make(width, height, passerRadius, tessellation, generator, rng)` | any pair (#386): the tessellation with `side = cellSize(passerRadius)`, then the generator for `need = passerRadius + MARGIN`, then the `Layout`. `squares`, `voronoi` and `penrose` are it with the dive game's three pairs |
| `WorldGen.make(width, height, passerRadius, outline, tessellation, generator, rng)` | an area that isn't a box (#387): the `Outline` is drawn first, then the tessellation over the whole box; only the cells whose centroid is inside the outline are kept, and of those the most that are joined edge to edge (`WorldGen.trim`, a `PolygonGraph.part`). The cells on the kept part's edge are `onBorder`, so no generator opens them, and they are the layout's `outer` cells |
| `Outline` | an area's shape in its box: `Box()` (the whole box: nothing is trimmed, so a level is exactly what `make` without an outline gives) or `Star(minVertices, maxVertices, minReach)` (`DEFAULT` 5, 12, 0.6): a star-shaped polygon round the box's middle, `outline.vertices` vertices, each at an evenly spaced angle moved by `outline.vertex[i].angle` (uniform in ±0.3 of a step) and `outline.vertex[i].reach` (uniform in [`minReach`, 1]) of the way to the box's edge. 69 of the first 100 are not convex; they cover 42 to 67% of the box. `type()` and `params()` for the log, `contains(polygon, p)` (even-odd) |
| `Layout.outer()`, `cellWalls(material, edge)` | the ids of the cells on the area's edge, and the walls with those of `edge` (the marker that the area ends there: bedrock, #397) and the rest of `material` |
| `CaveGenerator(settings).generate(graph, recorder, need)` | caves on any `CellGraph` (#312): each non-border cell is drawn open with a `bernoulli` (`cave[t].cell[c].open`, p = `fill`, attempt `t`), then `smoothing` rounds (open when more than half of itself and its neighbours are), regions under `minRegion` cells filled, the rest joined by `Connectivity.connect`, then `Passages` makes the cells a disc of radius `need` can reach (`Navigation`: open cells whose centroid is `need` or more from every wall, joined when the line between centroids is too) one region, widening a joining passage (opening the cells touching it) and filling in a region that border rock stops it joining. Stairs go on cells of that region more than 0.6 m from every wall: up drawn (`cave.stairsUp`), down the farthest from it. Spawns: other open cells over 0.4 m from every wall and 1 m from either stairs. Returns a `Plan(open, up, down, spawns)`. `Settings.DEFAULT`: fill 0.5, 4 rounds, minRegion 12; a cave a disc can reach fewer than `minRoomy` (40) cells of is drawn again, up to 10 attempts |
| `WorldGen.cellSize(r)` | `max(0.8, 2r / 0.75)` m: a one-cell passage fits an entity of radius `r` |
| `WorldGen.squares(width, height, passerRadius, settings, rng)` | a `Layout(walls, cells, openCells, up, down, spawns, draws)`: `Squares` with `RoomsAndCorridors` |
| `WorldGen.sites(width, height, r)` | `width * height / cellSize(r)^2`, rounded: a mean cell area near `cellSize^2` |
| `WorldGen.voronoi(width, height, passerRadius, settings, rng)` | a `Layout` (#312): `VoronoiGraph` with `sites(...)` sites and `settings.relaxations()` rounds (`VoronoiSettings.DEFAULT`: 3 rounds, `CaveGenerator.Settings.DEFAULT`), then caves for `need = passerRadius + MARGIN` (0.1 m), so a disc that far from every wall can go from the stairs up to the stairs down |
| `WorldGen.penrose(width, height, passerRadius, settings, rng)` | a `Layout`: `PenroseGraph` with side `settings.edge() * cellSize(r)` (`PenroseSettings.DEFAULT`: `MEAN_AREA_EDGE`, 1.11, so a rhomb's mean area is `cellSize²`, as for Voronoi sites), then caves as for Voronoi |

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

A Penrose level (`WorldGen.penrose`, 24 x 18 m, a 0.3 m passer: rhombs of side
1.11 `cellSize`, about 750 cells) had 133 to 226 walls over 200 seeds, 49 to 82
KB of wall JSON, and at least 273 spawns; each took at most 0.27 s. The tests
hold it to the Voronoi bounds (`CaveLevelContract`).

**Cost (#371).** Walls and sites are kept in a `PolygonGrid` (polygons by the
square buckets their bounding boxes touch), so `Clearance` (the nearest wall,
searched ever wider until it is inside the reach searched), `Navigation.joined`
(only walls whose boxes come within `need` of the line's box) and
`VoronoiCells` (the other sites by distance, a band at a time, in the order a
sort of all of them gives) look only at what is near. Every answer is the one a
scan over all of them gives: levels are byte for byte what they were (compared
for 20 seeds of each strategy and a 48 x 36 m level). Seed 11, Voronoi: 0.24 s at
24 x 18 m (675 cells), 0.32 s at 48 x 36 m, 1.7 s at 96 x 72 m (10,800 cells);
before, 0.3 s, 3.7 s and over 8 minutes. `Passages` tries as many rounds of
joining as there are cells (at least 100): each round joins or fills one region,
and a big level has more than 100. A grid isn't thread-safe; each is made and
used by one generation.

**Penrose passages.** A thin rhomb is 0.59 `edge` between its sides (0.65 `cellSize`), so a one-ring
widening can leave a way pinched below the passer's room. Once, widening a
region's way opened one cell that was a region of its own with no way out;
filling that in undid the widening, round after round. `Passages` now fills in
a region that comes back as it was after its way was widened.

## Adding a tessellation

The cave generator, `Navigation`, `Passages`, `Layout`'s cells and walls,
`CellMesh`, `libs/pathfinding` and the client take any `CellGraph`; nothing
there changed for Penrose. A new tiling is one `Tessellation` and works with
every `Generator` (`WorldGen.make`, `PairsTest`). What was hand work, and what
still is (#386: 1 to 3 and 6; #387: 4, 5, 7 and 8; #388: 9):

1. **Done (#386): tiling and generator are composed.** `WorldGen.make` takes
   any `Tessellation` and `Generator`; `squares`, `voronoi` and `penrose` and
   their `Settings` records stay as the dive game's pairs.
2. **Done (#386): generators answer alike,** a `Plan` with the stairs and
   spawns placed; `WorldGen` no longer places a room level's stairs.
3. **Done (#386): rooms and corridors on any tiling.** Where a cell is under
   `2 need` wide the corridors are widened as caves' passages are, and
   `PairsTest` checks every pair's stairs are joined for the passer.
4. **Exact shared vertices are each tiling's job** (Voronoi's line keys,
   Penrose's integer keys), but `PolygonGraph` now checks them when it is built
   and names the cell and edge: done. Hand it the polygons.
5. **Clipping to the box**: `BoxClip` is shared (Penrose uses it); Voronoi
   still clips by half-planes in `VoronoiCells`, which its vertex keys rest on.
   `SquareGrid` builds its polygons, border and bounds through `PolygonGraph`
   but keeps its own neighbour order.
6. **Done (#386): sizing is one rule,** a mean cell area of `side²`, each
   tessellation turning `side` into its own parameter (a square's side, a site
   count, a rhomb's side). The wall bounds of #186 rest on it: Penrose at
   `cellSize` sides had 276 walls, over the 250 the tests allow.
7. **Done (#387): areas needn't be boxes.** A tessellation still covers the
   box (`bounds()`, the clipping); an `Outline` trims it to any simple
   polygon, convex or not, and the cut edge is the area's outer cells.
   `LayoutCellsTest`'s check that a tiling ends only at its bounds holds for
   boxes; `OutlineTest` checks outlined areas end only at their outer cells.
8. **Done: the tests list tilings by name.** `LayoutCellsTest.TILINGS` is one
   line per tiling (name and `seed -> Layout`) and every test runs over it, 200
   seeds each; cave levels share `CaveLevelContract`, so a new cave tiling's
   test is a subclass of four methods.
9. **`libs/world-seed`'s `Levels`** has one `Strategy` per pair, its log's
   `tessellation` and `generator` from their `type()` and `params` (#386).
   Still open (#388): adding one changes which strategy an existing seed's
   `level.strategy` draw picks (levels already made are stored, so only new
   ones change).
