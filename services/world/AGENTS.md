# AGENTS.md: services/world

Owns games, areas and entities: the thin HTTP service over the world libs
(root `AGENTS.md`, the module table). It serves the resource API, the log API
and the **intents API**, and wires the libs together. The rules live in the
libs, so read the AGENTS.md of the one you need:
[`world-model`](../../libs/world-model/AGENTS.md) (resource objects, actions,
effects), [`world-engine`](../../libs/world-engine/AGENTS.md) (the advance),
[`world-db`](../../libs/world-db/AGENTS.md) (load, commit, lock, log tables),
[`world-seed`](../../libs/world-seed/AGENTS.md) (the test area),
[`resource-model`](../../libs/resource-model/AGENTS.md) (store, resource API,
change batches). Java 25, Spring Boot 3.5.7, package
`com.pessimisticit.scarcity.world`. Consumers: `subscriptions`, the web
client, `orbital`.

## Contract

### Ports and paths
- HTTP 8080. Resource API under `/api/world/resources` (`scarcity.resource-api.prefix`),
  served by the library's controller (list `?type=`, `/{id}/children`, `/{id}`; paging,
  ETag/304, errors: see the library's AGENTS.md).
- `GET /api/world/log` (`LogController`): checks the parameters (`area`
  required, `after` >= 0, `limit` >= 1 and clamped to 500, empty `kind` = all;
  400 `{"error"}` otherwise) and reads with world-db's `AreaLogReader`
  (`docs/log.md`, "Reading the log").
- `POST /api/world/intents` (`IntentController`): below.
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
`LazyJwksTest`). `sub` names the caller for intents (below).

A **service principal** sends `X-Api-Key: <key>` instead (`ApiKeyFilter`, before the
JWT filter). Keys are read once at startup by `ApiKeys` from `SCARCITY_WORLD_API_KEYS_DIR`:
one file per principal, the file name is the principal (`[a-z][a-z0-9-]*`), the content its key
(at least 16 characters, surrounding whitespace stripped). That is the k8s secret
`world-api-keys` mounted as a volume, one data key per principal (`orbital: <key>`); dot entries
(`..data`) are skipped. A missing directory, a bad name, a short key or two principals with the
same key fail startup; no directory configured means no keys. Only SHA-256 digests are kept and
compared in constant time against every key.
- A known key authenticates as `ApiKeyAuthentication(principal)` (`ROLE_SERVICE`). It reads
  like a user and posts intents only for entities whose `controller` is that service (403).
- An unknown or empty key: **401, empty body**. A key with an `Authorization` header, or the
  header twice: **400, empty body**.
- Keys and digests are never logged. A success logs the principal at DEBUG; a rejection logs
  `api-key rejected from <remote address>` at WARN, at most once per
  `scarcity.world.api-key-rejection-log-interval` (10s), with the count of those suppressed.

### Intents (contract: `docs/actions.md`, "Intents")
`IntentController` reads the body as `IntentRequest` with `WorldJson` (strict:
unknown keys, a missing `entity`, `action` or `params` are 400) and maps the
caller with `Callers`: a JWT is `Controller.User(sub)`, an API key is
`Controller.Service(principal)`, and anything else is an error. `Intents.post` runs one transaction in
a `world.advance` span, and `AreaAdvance.run` does the work:

1. `store.areaOf(entity)` (404 if none), then `AreaLock.begin(area, now)`;
2. `AreaRepository.load`; the entity must be in the area (404);
3. the entity's `controller` must equal the caller (403);
4. `Engine.advance(snapshot, intent, rng, head.nextSeq(), catalogue)`: the
   engine checks `ifVersion` (409), an action in progress (409) and the start
   rules (422);
5. `AreaRepository.commit(head, snapshot, result, null)`: the publisher puts
   the current span's traceparent (`world.advance`) on the batch.

A rejection is an `IntentRejected`, which rolls back: nothing is written, the
head row included. 200 is `{"area", "time", "logSeq", "version"}` (`version`
is the entity's after the advance, null if it was destroyed). Errors are
`{"error": "…"}`. Nothing runs without an intent: there is no scheduler.
Behaviours that can't start are logged as warnings (`AdvanceResult.blocked`).

Randomness: the `RandomGenerator` bean (`WorldApplication`) is the only
source. It is a `SplittableRandom`, which isn't thread-safe; that is fine
while advances of the one area run one at a time under its lock. A second
area needs a generator per area.

Metrics: `world.intents` (counter, tag `outcome`: `ok`, `malformed`,
`unknown_entity`, `not_controlled`, `stale`, `busy`, `invalid`, `error`) and
`world.advance.events` (distribution summary: the `action-start` and
`action-end` entries of one advance).

### Seed
At startup, an `ApplicationRunner` (`WorldConfig`, after Flyway) calls
world-seed's `Seeder.seed()`: the test area if there is no game yet. The ids,
states and versions are world-seed's (its AGENTS.md). `V3__start_clean` emptied
the tick loop's area, so the deployed world was seeded afresh (#111, #112).

## Configuration
| env var | default | meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scarcity` | |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `scarcity` / `scarcity` | k8s: from secret `postgres-secrets` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | k8s: `http://auth:8080/api/auth/.well-known/jwks.json` |
| `SCARCITY_WORLD_API_KEYS_DIR` | | directory of service principals' API keys (Security); k8s: secret `world-api-keys` as a volume |
| `SPRING_PROFILES_ACTIVE` | | `k8s`: ECS JSON logs on stdout, graceful shutdown, pool 5 |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | | as auth |

Properties: `scarcity.resource-api.prefix=/api/world/resources`,
`scarcity.world.log.snapshot-interval-minutes (60)|retention-hours (24)` (world-db's `LogSettings`,
`docs/log.md`), `scarcity.world.jwt-issuer`, `scarcity.world.api-keys-dir`,
`scarcity.world.api-key-rejection-log-interval (10s)`,
`scarcity.resource-changes.listen` (false: world only publishes).
The only volume is the API-key secret (mounted by #115). The container is read-only-fs friendly.

## Database
Schema `world`, Flyway (`schemas`, `default-schema: world`, `create-schemas: true`).
`V1__resources.sql` is a **copy** of `libs/resource-model/.../db/resource-model/resources.sql`
(never edit a released migration; add a new `V<n>__...` for changes). `V2__area_log.sql`
holds world-db's log tables. `V3__start_clean.sql` empties the tables once (the clean start). The library's `resources`
table is unqualified, so the Hikari pool sets `spring.datasource.hikari.schema=world`
(search_path); without it the store can't find the table. Never read another service's schema.
`libs/world-db` and `libs/world-seed` run their tests against these migration files as they are (their `modules.yaml`
`paths` include this directory), so a new migration also rebuilds those libraries.

## Decisions
- Seeding is an `ApplicationRunner` (after Flyway); a concurrent replica seeding at the same
  time loses on the primary key and is ignored (world-seed).
- Observability package copied from auth with only the package name changed (shared lib: #22).
  The advance's span is a Micrometer `Tracer` span (JDBC spans are dropped inside it by
  the "only inside an observation" rule copied from auth; fine, one span per advance).
- Security: custom `JwtDecoder` (Nimbus, `withJwkSetUri`, `JwtValidators.createDefaultWithIssuer`),
  `anyRequest().permitAll()` outside `/api/world/**` because nothing else is served on 8080.
- The 403 check is the service's, not the engine's: who the caller is is an HTTP matter.

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
Docker). This suite is the HTTP and wiring layer; the engine and the database
rules are tested in the libs. `WorldTest` is the shared base (one Spring
context, a JWKS HTTP server from `TestSupport`, the test area truncated and
re-seeded before each test, every published batch collected).
- `IntentApiTest`: a 200 with its body and metrics; one batch with the
  `logSeq` and a traceparent, and the log `snapshot` → `intent` →
  `action-start`; every status (400, 401, 403, 404, 409 stale and busy, 422)
  writes nothing; two concurrent intents on one version give 200 and 409; a
  service principal (a real API key) only for what it controls; nothing advances for 2 s without intents. The user-controlled
  entity is test-inserted (`HERO`).
- `ResourceApiTest`: JWT enforcement (no token, junk, wrong key, wrong issuer,
  expired), the seeded area with ETag/304, actuator on the management port.
- `LogApiTest`: paging, `kind`, the 500 clamp, bad parameters, an unknown area.
- `ApiKeyTest`: a valid key posts for its own entity and reads; another
  principal's or a user's entity is 403; wrong, empty, doubled and key-plus-JWT
  give 401/400 with empty bodies; captured logs never contain a key. `WorldTest`
  writes two keys (`orbital`, `stranger`) to a temp directory.
- `ApiKeysTest`: the k8s secret-volume layout, blank configuration, and each
  malformed directory failing startup.
- `LazyJwksTest`: starts with an unreachable JWKS and no API keys (every key is a 401).

## Open follow-ups
- Map a JWKS fetch failure to 503 instead of 500.
- No ECS-logging test (auth has one in a separate JVM); checked by running the image with the k8s profile.
