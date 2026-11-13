# AGENTS.md: services/world

Owns games, areas and entities and serves them through the generic resource API.
First game service; currently the **test area** (a room, a pillar, an orbiting
mobile). Built on `libs/resource-model` (read its AGENTS.md first). Java 25,
Spring Boot 3.5.7, package `com.pessimisticit.scarcity.world`. Tracking: #29.
Consumers: `subscriptions` (#30) and the web client (#31).

## Contract

### Ports and paths
- HTTP 8080. Resource API under `/api/world/resources` (`scarcity.resource-api.prefix`),
  served by the library's controller (list `?type=`, `/{id}/children`, `/{id}`; paging,
  ETag/304, errors: see the library's AGENTS.md). No hand-written endpoints here.
- Actuator on **8081** (`management.server.port`, port name `metrics`): `health`
  (liveness/readiness probes enabled; readiness includes `db`) and `prometheus`. Unauthenticated,
  never in a Service or route. Same wiring as auth (`observability.yml`, `observability` package).

### Security
Everything under `/api/world/**` needs `Authorization: Bearer <JWT>` minted by `auth`:
RS256, signature checked against `AUTH_JWKS_URI`, `iss` must be `scarcity-auth`
(`scarcity.world.jwt-issuer`), `exp` checked. Failure: **401, empty body** (with
`WWW-Authenticate`), no redirect, no CSRF, stateless. The JWKS is fetched lazily on the
first token, so startup does not need auth. Known limit: if the JWKS can't be fetched,
Spring Security answers **500** (not 401/503) to requests carrying a token (tested in
`LazyJwksTest`). `sub`/`username` aren't used yet (no per-user authorization).

### Seed (startup, idempotent, one transaction; `Seeder`)
If `game:...0001` exists nothing is written. Ids (`WorldIds`) are fixed and part of the contract:

| id | parent | state |
|---|---|---|
| `game:00000000-0000-4000-8000-000000000001` | none | `{"name":"Test area"}` |
| `area:00000000-0000-4000-8000-000000000002` | game | `{"name":"Test room","width":10.0,"height":8.0}` (x in [0,10), y in [0,8), origin bottom-left) |
| `entity:00000000-0000-4000-8000-000000000003` | area | `{"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0}}` |
| `entity:00000000-0000-4000-8000-000000000004` | area | `{"kind":"mobile","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,"actions":["orbit-step","wait"],"behaviour":{"kind":"random","choices":[{"weight":1.0,"action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":3.0,"arc":0.5}}},{"weight":1.0,"action":{"type":"wait","params":{"duration":1.0}}}]}}` |

The area gets `clock` on its first tick (not at seed). **Upgrade:** if the game already
exists, `Seeder` still checks the orbiter and rewrites it once with `updateState`
(version +1) if it has an old shape: the 2026-11-11 `orbit` key (position kept, `orbit`
removed, `speed`, `actions`, `behaviour` added) or the 2026-11-12 `repeat` behaviour with
`actions: ["orbit-step"]` (position, speed and any `action` in progress kept; its orbit-step
params are reused). Both land on the `random` shape above. Idempotent (a `random`-shaped
orbiter is untouched); a fresh seed leaves the orbiter at version 1.

Versions after a fresh seed: **game 2, area 3, pillar 1, orbiter 1.** The library's rule
is that creating a child bumps its parent, so the parents are not at 1 (the task said
1; this is the faithful result of the version rule). Entities are the area's children in
creation order: pillar, orbiter. jsonb doesn't keep key order, so state key order in
responses is not the order above.

### Tick loop (`TickLoop` -> `Ticker.tick()`)
Implements `docs/resources.md` "Game time" and "Actions".
- `@Scheduled(fixedDelay = 200)` (`scarcity.world.tick.delay-millis`), Spring's single
  scheduler thread. `scarcity.world.tick.enabled=false` turns it off (tests).
- One tick = one transaction inside a `world.tick` span; failure rolls back and publishes
  nothing, the loop logs and goes on. If the area isn't seeded yet, nothing happens.
- Game time = `clock.gameTime + (nowMillis - clock.wallTime)/1000 * clock.rate` (area state
  key `clock`, written on the first tick as `{0.0, now, 1.0}`). `Clock` is a bean.
- Entities are loaded with `store.children(area, ENTITY)` (paged by 200). Events: an
  entity's `action.end`, or "start now" for an idle entity with a `behaviour`. Due events
  (time <= game time) run in order of time, ties by id. Ending: apply the effect if `effect`
  is `end`, remove `action`, then start the behaviour's next action **at the ended
  action's `end`**. Starting: type must be known and in the entity's `actions`, duration
  from the type (must be finite and > 0), write `action {type, params, start, end, effect}`,
  apply the effect now if `start`. A behaviour that can't start (unknown type, not listed,
  bad duration) logs a warning and leaves the entity idle for that tick.
- Behaviour kinds: `repeat` (the same action every time, no `draw`) and `random`
  (`choices: [{weight, action:{type, params}}]`). For `random` all choices are validated
  first (non-empty; each weight a positive finite JSON number; each action startable); an
  invalid behaviour starts nothing and consumes no draw. Then one `u = generator.nextDouble()`
  per start, `Behaviours.pick(weights, u)` (first index whose cumulative weight share is
  strictly greater than `u`), the chosen action starts and records
  `draw {dist:"categorical", weights:[...], u, value:index}`.
- Pull-back: if the earliest due `action.end` is more than `scarcity.world.tick.max-lag`
  (5.0) before game time, a new clock anchor `{earliest, now, rate}` is written to the area
  and the tick runs at game time `earliest` (so one step, no burst).
- One write per changed resource (area first if its clock changed, then entities by id), one
  `ChangeBatch` on `resource_changes` with `area` = the area id, `traceparent` of the span;
  each change's `patch` is the full new state (patches replace `state`). No due events and
  no clock change: nothing written, nothing published. Hard cap of 100,000 events per tick
  (throws, rolls back).
- Action types are `ActionType` beans (`name`, `effect` START/END/NONE, `duration`, `apply`).
  `Wait` (`wait`): effect none (the engine applies nothing), duration `params.duration`.
  The only source of randomness is the `RandomGenerator` bean in `WorldApplication`
  (unseeded `SplittableRandom`), injected into `Ticker`; tests pass a seeded or scripted one
  (`ScriptedRandom`).
  `OrbitStep` (`orbit-step`): effect end, duration `abs(arc)/state.speed`, new angle =
  `atan2` of the current position around `center` + `arc/radius`, position from `center`
  and `radius` (no drift). Tests build `new Ticker(store, publisher, tx, clock, tracer, generator, List.of(types), maxLag)`.

### Area log (contract: `docs/log.md`)
Classes: `AreaLog` (bean: head lock, append, prune, read), `TickLog` (one per tick: entries
collected in memory, versions resolved and rows written by `commit`), `LogEntry`/`LogChange`,
`LogController` (`GET /api/world/log`, JWT like the rest). `Ticker` only calls `TickLog`.
- Tables (`V2__area_log.sql`): `area_log(area_id, seq, game_time, wall_time, kind, cause, root,
  resource, body jsonb{reason?,action?,draws,changes})` PK `(area_id, seq)`; `area_log_head(area_id,
  last_seq, last_snapshot_at)`. `Ticker` calls `begin` at the start of every tick, which inserts
  the head if missing and locks it `FOR UPDATE` (gapless `seq`; a second ticker waits, then
  continues from the committed head). Entries and head commit in the tick transaction.
- Order inside a tick: `clock-anchored` (first-tick / pull-back), `snapshot` (if due), then per event
  `action-end` then `action-start`. A started action gets `seq` (allocated before the state is
  final) in `action.seq`; an ended action without `seq` gives `cause: null`.
- `changes[].version` is resolved at commit from the versions the tick wrote. A snapshot lists
  entities at the version they had before the tick (they may be written later in the same tick); the
  area's snapshot version is the tick's written one when the anchor changed it.
- **Deviations/choices:** snapshot `time` = min(tick game time, earliest due action end) so `time`
  never decreases with `seq` on a late tick; the entry's `action` omits the state's `draw` key (the
  draw is in `draws`, with `"for":"behaviour"`); the area entries have `resource: null`.
- A tick with nothing due and no snapshot due writes nothing (only the head lock is taken). A
  snapshot-only tick writes one entry and the head, no resource version, no publish.
- Pruning runs in the tick that writes a snapshot: delete `seq` < the newest snapshot with
  `wall_time` older than the retention. `seq` never restarts.
- API: `area` required (400), `after` >= 0 (400), `limit` default 100, <1 -> 400, >500 clamped.
  Unknown area: empty log.
- Properties: `scarcity.world.log.snapshot-interval-minutes` (60), `scarcity.world.log.retention-hours` (24).
- Not yet: effects on other resources, created/destroyed entries, client view.

## Configuration
| env var | default | meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scarcity` | |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `scarcity` / `scarcity` | k8s: from secret `postgres-secrets` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | k8s: `http://auth:8080/api/auth/.well-known/jwks.json` |
| `SPRING_PROFILES_ACTIVE` | | `k8s`: ECS JSON logs on stdout, graceful shutdown, pool 5 |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | | as auth |

Properties: `scarcity.resource-api.prefix=/api/world/resources`,
`scarcity.world.tick.enabled|delay-millis (200)|max-lag (5.0)`, `scarcity.world.log.*` (see Area log), `scarcity.world.jwt-issuer`,
`scarcity.resource-changes.listen` (false: world only publishes).
No volumes. The container is read-only-fs friendly.

## Database
Schema `world`, Flyway (`schemas`, `default-schema: world`, `create-schemas: true`).
`V1__resources.sql` is a **copy** of `libs/resource-model/.../db/resource-model/resources.sql`
(never edit a released migration; add `V2__...` for changes). The library's `resources`
table is unqualified, so the Hikari pool sets `spring.datasource.hikari.schema=world`
(search_path); without it the store can't find the table. Never read another service's schema.

## Decisions
- Seeding is an `ApplicationRunner` (after Flyway); a concurrent replica seeding at the same
  time loses on the primary key and is ignored (`DuplicateKeyException`).
- `Json` in resource-model is package-private, so `Seeder` has its own `ObjectMapper`.
- Observability package copied from auth with only the package name changed (shared lib: #22).
  Spans for ticks come from a Micrometer `Tracer` span (JDBC spans are dropped inside it by
  the "only inside an observation" rule copied from auth; fine, one span per tick).
- Security: custom `JwtDecoder` (Nimbus, `withJwkSetUri`, `JwtValidators.createDefaultWithIssuer`),
  `anyRequest().permitAll()` outside `/api/world/**` because nothing else is served on 8080.

## Run locally
```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
docker run -d --name world-pg -p 5432:5432 -e POSTGRES_DB=scarcity -e POSTGRES_USER=scarcity -e POSTGRES_PASSWORD=scarcity postgres:18.6
mvn -B -f ../../pom.xml -pl services/world -am spring-boot:run
```
Point `AUTH_JWKS_URI` at a running auth (it defaults to localhost:8080, which is world itself;
run world with `SERVER_PORT=8082`) and get a token from `POST /api/auth/login`.

## Tests
`mvn -B -f ../../pom.xml -pl services/world -am verify` (Testcontainers postgres:18.6, needs
Docker). `WorldIntegrationTest`: schema + seed + idempotence, the action engine on a controllable
clock (first tick, chaining at `end` even when late, three steps in one tick with one write,
pull-back after 60 s, silent idle ticks, 1,000 steps keep the radius, a test-only `start`-effect
type, the old-orbiter upgrade run twice, a `repeat` orbiter with an action in progress upgraded once, a scripted
draw sequence 0.25/0.75 with the `draw` record, nine invalid `random` behaviours that start nothing and draw nothing, three chained
draws in one tick with one write, `repeat` has no `draw`, 2,000 seeded starts within 0.45..0.55 with the radius kept), rollback on failure, JWT enforcement (no token, junk, wrong key, wrong issuer,
expired) against a real JWKS HTTP server in the test, the three API operations with ETag/304,
actuator on the management port. `AreaLogTest` (log writing, invariants over 250 ticks, replay, pruning, rollback; wipes and re-seeds the shared DB per test, see `LogTestBase`), `LogApiTest` (log endpoint). `BehavioursTest`: `pick` boundaries. `LazyJwksTest`: starts with an unreachable JWKS.

## Open follow-ups
- Map a JWKS fetch failure to 503 instead of 500.
- Tick loop runs in every replica; area ownership (lease) isn't built (`docs/resources.md`, "Ticks and ownership").
- No ECS-logging test (auth has one in a separate JVM); checked by running the image with the k8s profile.
