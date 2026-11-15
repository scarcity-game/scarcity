# world

The game service: a thin HTTP layer over the world libs (`libs/world-model`,
`world-engine`, `world-db`, `world-seed`). It owns the test area (a 10x8 room
with a pillar and the orbiter, seeded at startup) and serves:

- `GET /api/world/resources…`: games, areas and entities through the generic
  resource API, with ETags.
- `GET /api/world/log?area=<id>&after=<seq>&limit=<n>&kind=<kind>`: the area
  log (`docs/log.md`).
- `POST /api/world/intents`: a controller's next action for an entity it
  controls (`docs/actions.md`, "Intents"). Each intent runs one advance in one
  transaction and publishes one change batch, which `subscriptions` forwards
  to browsers.

Game time passes only when an intent arrives; there is no tick loop. All
requests need a JWT from `auth`.

See [AGENTS.md](AGENTS.md) for the contract, configuration and how to run it.

```
mvn -B -f ../../pom.xml -pl services/world -am verify
docker build -f services/world/Dockerfile -t scarcity-world .   # from the repo root
```
