# world-gen

Generated area layouts (#234). A tessellation is laid over the area as a
graph of cells, and a generator chooses which cells are open floor. The rest
becomes `wall` entities with convex polygons. Positions stay floats:
the cells shape only where the walls go, and they're never stored.

The first pair is squares with rooms and corridors:
`WorldGen.squares(width, height, passerRadius, settings, rng)` returns the
walls, where the stairs up and down go, spawn points and every draw, in
order. Pure Java on `libs/world-model`. `Levels` uses it in #311.

The others are caves on a Voronoi tessellation (`WorldGen.voronoi`) and on
an aperiodic Penrose rhomb tiling (`WorldGen.penrose`).

See [AGENTS.md](AGENTS.md) for the API and the rules it keeps.

    cd libs/world-gen
    mvn -B -f ../../pom.xml -pl libs/world-gen -am verify

No Docker needed.
