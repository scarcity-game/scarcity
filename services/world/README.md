# world

The game service. It owns the test area (a 10x8 room with a pillar and an orbiting
mobile), moves the orbiter once a second and serves games, areas and entities through
the generic resource API at `/api/world/resources` (JWT from `auth` required).

Each tick publishes one change notification through `libs/resource-model`; the
`subscriptions` service forwards it to browsers.

See [AGENTS.md](AGENTS.md) for the contract (ids, state shapes, tick, env vars) and how to run it.

```
mvn -B -f ../../pom.xml -pl services/world -am verify
docker build -f services/world/Dockerfile -t scarcity-world .   # from the repo root
```
