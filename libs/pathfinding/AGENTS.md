# AGENTS.md: libs/pathfinding

Paths round an area's obstacles, for controllers (package
`com.pessimisticit.scarcity.pathfinding`; #237, epic #234). Contract:
`docs/resources.md` ("Shapes", "Walls") and `docs/actions.md`
("Interruption"): a path is only as good as the `step`s it becomes.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope (the enforcer rule `pure-pathfinding` bans Spring, JDBC and
`resource-model`). No randomness, no clock. Tests need no Docker. Lint
applies with no baseline entries.

## API
| type | what |
|---|---|
| `Pathfinder.path(view, moverId, to)` | `Optional<List<Position>>`: the waypoints from the mover's position to `to`, each the end of a leg, the last one `to` and the start not among them; an empty list when the mover is at `to`; empty when it can't get there. The same view gives the same path, whatever order its entities came in |
| `Pathfinder.MARGIN` | 0.05 m: how much further than its radius a path keeps the mover from obstacles' corners |

## How it works
- **Obstacles** are what `Contacts` stops a mover at: every entity other
  than the mover with a `shape()` that doesn't `overlap`. Walls are such
  entities; there is no cell graph (the tessellation isn't stored, docs/decisions.md
  2026-11-23), so this lib derives what it needs from the shapes alone.
  **Changing (#349):** from 2026-11-25 areas keep their cells, and #355
  replaces this with A* over floor cells.
- **Waypoints** (`Corners`): each obstacle grown by the mover's radius plus
  `MARGIN`. A polygon's vertex goes where its two edges, moved out, meet; a
  sharp one (interior angle under 60°) gets three points on an arc instead.
  A circle becomes a regular octagon outside its grown circle.
- **Legs**: a leg joins two waypoints when `Contacts.first` (world's own
  check, with the mover at the leg's start, moving along it) finds nothing
  before its end, with the radius grown by `MARGIN / 2`, so a `step` along it
  isn't stopped by a rounding error. The last leg, into `to`, is checked at
  the mover's own radius: a target such as stairs may lie nearer a wall than
  the margin.
- **Dropped waypoints:** one within the clearance (radius plus `MARGIN`) of
  any obstacle but its own. Walls one cell each (#234) meet along straight
  runs, and every shared vertex grows a waypoint exactly the clearance from
  the next wall; a path never bends there. Without this a generated level's
  plan took seconds; with it, under 0.1 s (24 × 18 m, 200 walls).
- **Search**: A* from the mover to `to`, Euclidean cost and heuristic, legs
  tested as nodes are expanded; ties by waypoint index, which follows entity
  id, so it is deterministic.
- Other movers count where they are now. The caller plans again each turn.

## Limits (follow-ups when they bite)
- Cost: up to n² leg tests for n waypoints (four per rectangular wall), each
  over every obstacle. Fine for today's levels (a handful of walls, the tests
  take milliseconds); generated levels with hundreds of walls may want a
  spatial index, or a cell-based mesh from world-gen.
- A gap is passable when the mover fits through with `MARGIN / 2` to spare,
  not when it fits exactly.
