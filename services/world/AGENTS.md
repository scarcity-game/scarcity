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
| `entity:00000000-0000-4000-8000-000000000004` | area | `{"kind":"mobile","name":"orbiter","position":{"x":8.0,"y":4.0},"orbit":{"center":{"x":5.0,"y":4.0},"radius":3.0,"period":60.0}}` (`period` in seconds) |

Versions after a fresh seed: **game 2, area 3, pillar 1, orbiter 1.** The library's rule
is that creating a child bumps its parent, so the parents are not at 1 (the task said
1; this is the faithful result of the version rule). Entities are the area's children in
creation order: pillar, orbiter. jsonb doesn't keep key order, so state key order in
responses is not the order above.

### Tick loop (`TickLoop` -> `Ticker.tick()`)
- `@Scheduled(fixedDelay = 1000)` (`scarcity.world.tick.delay-millis`), Spring's single
  scheduler thread. `scarcity.world.tick.enabled=false` turns it off (tests).
- One tick = one transaction inside a `world.tick` span: read orbiter, compute position,
  `store.updateState` (full new state, only `position` differs), `publisher.publish` one
  `ChangeBatch`. If it throws, the transaction rolls back (nothing published) and the loop
  logs and keeps ticking. If the orbiter doesn't exist yet, the tick does nothing.
- Position is a pure function of wall-clock time:
  `angle = 2*pi * (epochMillis mod periodMillis) / periodMillis`, counter-clockwise (y up),
  `pos = center + radius*(cos angle, sin angle)`. t=0 -> (8,4); t=period/4 -> (5,7).
  `period`, `radius`, `center` are read from the orbiter's `orbit` state.
- Notification on channel `resource_changes`: `area` = the area id, one change
  `{id: orbiter, type: "entity", version: <new>, patch: <the full new state object>}`,
  `traceparent` of the `world.tick` span. **Patch semantics for clients: a patch replaces
  `state`** (deltas may come later). Only the orbiter changes; area/pillar/game versions
  stay put.
- `Clock` is a bean (`WorldApplication`); tests build `new Ticker(store, publisher, tx, clock, tracer)`.

## Configuration
| env var | default | meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scarcity` | |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `scarcity` / `scarcity` | k8s: from secret `postgres-secrets` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | k8s: `http://auth:8080/api/auth/.well-known/jwks.json` |
| `SPRING_PROFILES_ACTIVE` | | `k8s`: ECS JSON logs on stdout, graceful shutdown, pool 5 |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | | as auth |

Properties: `scarcity.resource-api.prefix=/api/world/resources`,
`scarcity.world.tick.enabled|delay-millis`, `scarcity.world.jwt-issuer`,
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
Docker). `WorldIntegrationTest`: schema + seed + idempotence, `Ticker` positions/versions/
notification, rollback on failure, JWT enforcement (no token, junk, wrong key, wrong issuer,
expired) against a real JWKS HTTP server in the test, the three API operations with ETag/304,
actuator on the management port. `LazyJwksTest`: starts with an unreachable JWKS.

## Open follow-ups
- Map a JWKS fetch failure to 503 instead of 500.
- Tick loop runs in every replica; area ownership (lease) isn't built (`docs/resources.md`, "Ticks and ownership").
- No ECS-logging test (auth has one in a separate JVM); checked by running the image with the k8s profile.
