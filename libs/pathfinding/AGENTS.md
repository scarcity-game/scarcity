# AGENTS.md: libs/pathfinding

Paths round an area's obstacles, for controllers (package
`com.pessimisticit.scarcity.pathfinding`; #237, epic #234). Contract:
`docs/resources.md` ("Shapes", "Walls", "Cells") and `docs/actions.md`
("Interruption"): a path is only as good as the `step`s it becomes.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope (the enforcer rule `pure-pathfinding` bans Spring, JDBC and
`resource-model`). No randomness, no clock. Tests need no Docker. Lint
applies with no baseline entries.

## API
| type | what |
|---|---|
| `Pathfinder.path(view, moverId, to)` | `Optional<List<Position>>`: the waypoints from the mover's position to `to`, each the end of a leg, the last one `to` and the start not among them; an empty list when the mover is at `to`; empty when it can't get there or the budget ran out. The same view gives the same path, whatever order its entities came in |
| `Pathfinder.path(view, moverId, to, settings)` | the same with `Settings(budget, near, crowding)`: at most `budget` cells expanded (`DEFAULT` 5000), other movers' cells not entered within `near` cells of the start (3) and `crowding` m dearer beyond (2.0) |
| `Pathfinder.MARGIN` | 0.05 m: how much further than its radius a path keeps the mover from obstacles' corners |

## How it works (#355)
Over the area's cells (docs/resources.md, "Cells"); a view with none (an
area made before #353) falls back to the visibility graph below, over every
obstacle.
1. **A straight line first.** If `Contacts.first` finds the leg to the target
   clear, that's the path, with no search.
2. **A\* over floor cells** (`CellSearch`, over `FloorGraph`'s arrays and an
   `IntHeap`). From the cell holding the mover to the cell holding the target
   (the nearest floor cell if none holds it; `Buckets` finds it). An opening is
   passable when `r + 0.6 MARGIN <= clearance` of both cells (the start and goal
   cells are exempt) and `2(r + 0.6 MARGIN) <= width` (`Pathfinder.TIGHT` is the `0.6 MARGIN`: the
   least `Erosion` can cut an opening to, so a crossing it lets through always
   has a stretch to cross at; a gap of exactly `2r` is no way, #438). The
   clearance gets the margin too: a cell between two walls whose centroid is
   just over `r` from them can be narrower than `2r + MARGIN` corner to corner,
   and the `Mender` then went round from inside it, back past the start
   (`StuckDiverInAGapTest`). Cost: from a cell's point (its centroid; the
   mover in the start cell, the target in the goal's) through the opening's
   midpoint to the next; heuristic: the straight line. At most `budget` cells
   are expanded, then there is no path.
3. **Other movers** (obstacles whose position is in a floor cell; walls are in
   their own) count in every floor cell within their extent plus the
   clearance. Within `near` cells of the start those cells aren't entered and
   their corners count as solid; beyond, each costs `crowding` m more.
4. **Erosion** (`Erosion`). Each opening is cut to where the mover's centre
   may cross it: the longest stretch at least `r + MARGIN` from every solid
   cell touching either cell (`r + 0.6 MARGIN` where nothing is that clear:
   openings between `2r` and `2(r + MARGIN)` wide). Each cut end remembers the
   wall corner that stopped it.
5. **String-pulling** (`Funnel`): Mononen's funnel through the eroded
   openings, giving the bends: corners, each with the side the path passes it
   on. A bend at a vertex where only floor cells meet is dropped if the leg
   past it is clear.
6. **Rounding** (`Rounding`, `Refiner`): each corner becomes a circle of its
   clearance; the legs follow the tangents between circles, and each arc is a
   few points on a polygon just outside its circle, at most 45° apart. Then
   each leg is checked (`Probe`): one that touches a wall gets that wall's
   nearest corner as another circle, an arc that swings into a wall is drawn
   tighter, and an arc that turns back on itself is dropped, and its corner is not added again
   (it would loop until the rounds ran out, and the plan would depend on which round
   that was).
7. **Mending** (`Mender`): a leg still not clear is replaced by a visibility
   graph over the obstacles within 2 m, from its start to the next waypoint
   the mover can stand at. If that finds nothing, the leg stays, the `step` is
   cut short, and the controller plans again next turn.

Legs are checked as `step` is (`Contacts.first`, the mover at the leg's start
moving along it) at the radius grown by `MARGIN / 2`, so a `step` along it
isn't stopped by a rounding error; the last leg, into the target, at the
mover's own radius (a target such as stairs may lie nearer a wall than the
margin). Other movers count where they are now. The caller plans again each
turn.

**The visibility graph** (`VisibilityGraph`, `Corners`; the old planner, now
the fallback and the mender): waypoints are each obstacle grown by the
mover's radius plus `MARGIN` (a polygon vertex where its moved-out edges meet,
three points on an arc for one under 60°; a circle as an octagon), dropping
those within the clearance of another obstacle; A\* over the clear legs. Up
to n² leg tests for n waypoints, each over every obstacle (#346).

## Cost (#355)
On a 48 x 36 m Voronoi level (2,700 cells, 521 entities, `CellPathTest`):
about 42 ms and 49 MB allocated per plan, 753 cells expanded, no leg mended.
Memory is the arrays per floor cell, and the search is bounded by `budget`.
Building `FloorGraph` each plan is linear in the cells; a controller that
plans often on huge levels may want to keep it between turns.

## Tests
`PathfinderTest` (rooms of rectangular walls, cells from `Grid`) and
`CellPathTest` (levels from `libs/world-gen`, a test dependency: 20 seeds of
each strategy, every leg checked; a passage a grid would lose; a pinch a grid
would pass; the budget; another mover; time and memory on a large level) and
`StuckDiverTest` (#438: the cells and walls of a live level near a diver that
stepped between two points for ever, 160 KB in `src/test/resources`; following the
plan from every floor cell of it reaches the stairs without revisiting a point).
World-gen makes levels much bigger than 48 x 36 m slowly (#371).
