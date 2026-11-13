# world

The game service. It owns the test area (a 10x8 room with a pillar and an orbiting
mobile), runs the generic action system (game time, actions with durations; the orbiter
randomly picks `orbit-step` or `wait` (one time unit); each pick is recorded with its draw) and serves games, areas and entities through
the generic resource API at `/api/world/resources` (JWT from `auth` required).

The tick loop runs every 200 ms. A tick with no due action event (and no snapshot due)
writes and publishes nothing; otherwise it publishes one change batch through `libs/resource-model`, which the
`subscriptions` service forwards to browsers.

Every change is also recorded in the **area log** (`docs/log.md`): entries `snapshot`,
`clock-anchored`, `action-start`, `action-end`, `effect`, `action-cancelled` with exact game times, causes, draws and
resulting state, read with `GET /api/world/log?area=<id>&after=<seq>&limit=<n>&kind=<kind>`
(JWT required). Snapshots every 60 min of wall time; entries older than the newest snapshot
past 24 h are pruned (`scarcity.world.log.snapshot-interval-minutes`, `retention-hours`).

The tick also has an **effect engine** (`docs/actions.md`, "Effects"): actions can set off
effects (generic `create` and `destroy` so far), each logged with its cause. Nothing in the
deployed area uses it yet.

See [AGENTS.md](AGENTS.md) for the contract (ids, state shapes, tick, env vars) and how to run it.

```
mvn -B -f ../../pom.xml -pl services/world -am verify
docker build -f services/world/Dockerfile -t scarcity-world .   # from the repo root
```
