# world

The game service. It owns the test area (a 10x8 room with a pillar and an orbiting
mobile), runs the generic action system (game time, actions with durations; the orbiter
randomly picks `orbit-step` or `wait` (one time unit); each pick is recorded with its draw) and serves games, areas and entities through
the generic resource API at `/api/world/resources` (JWT from `auth` required).

The tick loop runs every 200 ms. A tick with no due action event writes and publishes
nothing; otherwise it publishes one change batch through `libs/resource-model`, which the
`subscriptions` service forwards to browsers.

See [AGENTS.md](AGENTS.md) for the contract (ids, state shapes, tick, env vars) and how to run it.

```
mvn -B -f ../../pom.xml -pl services/world -am verify
docker build -f services/world/Dockerfile -t scarcity-world .   # from the repo root
```
