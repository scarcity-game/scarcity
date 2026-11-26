# pathfinding

The way for a moving circle round an area's obstacles (#237, epic #234):
`Pathfinder.path(view, moverId, to)` gives the waypoints of a path of
straight legs from the mover to `to`, round every entity with a shape that
doesn't `overlap` (walls are such entities), or empty when there is none.
It searches the area's floor cells with A\* within a budget and pulls the
path tight through their openings (#355).
Controllers use it and send one `step` per leg; world never plans paths. Pure
Java on `libs/world-model`.

See [AGENTS.md](AGENTS.md) for how it works and its limits.

    cd libs/pathfinding
    mvn -B -f ../../pom.xml -pl libs/pathfinding -am verify

No Docker needed.
