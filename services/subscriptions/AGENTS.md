# AGENTS.md: services/subscriptions

Holds the client WebSockets. It `LISTEN`s for `resource_changes` batches (through
`libs/resource-model`, never `pg_notify` directly), keeps a map area id -> sockets, and sends each
socket the batches (deltas, #186) of the areas it subscribed to, plus a version checksum on subscribe
and every ~30 s. Java 25, Spring Boot 4.1, package `com.pessimisticit.scarcity.subscriptions`.
Tracking: #30. Client: #31 (web). Protocol source of truth: `docs/websocket.md`; version rules: `docs/resources.md`.
Owns no schema, no Flyway, and does **not** serve `/resources` (the library's
`ResourceApiAutoConfiguration` is excluded in `SubscriptionsApplication`).

## Ports and routes
- HTTP **8080**: only `/api/subscriptions/ws` (WebSocket upgrade; any `Origin` accepted: the JWT is
  in the first frame, there are no cookies). No Spring Security filter chain: HTTP is open.
- Actuator **8081** (port name `metrics`): `health` (readiness = `readinessState`, `db`,
  `changeListener`), `prometheus`. Tracing and metrics from `libs/observability`.
- The HTTPRoute for `/api/subscriptions` needs `timeouts: {request: 0s}` (`docs/websocket.md`).

## Protocol as implemented
JSON text frames, one object each, unknown fields ignored.
- Client -> server: `auth{token}` (first frame), `subscribe{area}`, `unsubscribe{area}`,
  `subscribe{turns:true}`, `unsubscribe{turns:true}`, `ping`.
- Server -> client: `ready{user,username}`, `subscribed{area}` immediately followed by
  `checksum{area,checksum,logSeq?}`, `changes{area,changes[],fromLogSeq?,logSeq?,part?,parts?,traceparent?}`,
  `reload{area,logSeq?,traceparent?}`,
  `turn{area,entity,version,since}`, `unsubscribed{area}`, `subscribed{turns:true}`,
  `unsubscribed{turns:true}`, `pong`, `error{message}`.
- Close codes: **4400** any frame before `auth` that isn't a valid `auth` frame (including
  non-JSON). **4401** no `auth` within `auth-timeout` (5 s), missing/invalid token (bad signature,
  wrong `iss`, expired, no `sub`/`exp`), world answered 401 on subscribe, or the token's `exp`
  passed (scheduled close). **1009** (Tomcat) for a text frame over 64 KB. Binary frames: 1003.
- Interpretations of gaps in the text (flag to clients):
  - `auth` with a missing/empty `token` -> 4401 (not 4400: it is an auth frame).
  - After `auth`: non-JSON/non-object frame -> `error "malformed frame"`; missing or non-string
    `type` and unknown `type` -> `error "unknown type"`; a second `auth` -> `error "unknown type"`.
  - An area id must be a well-formed `area:...` id (`ResourceIds.matches(AREA, id)`); anything else,
    including `entity:...`, -> `error "invalid area id"`. `unsubscribe` validates the same way.
  - `unsubscribe` always answers `unsubscribed`, even if the socket wasn't subscribed.
  - Subscribing twice: `subscribed` + `checksum` are sent again, nothing else changes.
  - world 404 -> `error "unknown area"`; other world status -> `error "could not load area: world
    answered N"`; world unreachable -> `error "could not load area: world unavailable"`. Nothing
    is subscribed in any error case.
  - `changes` is `{"type":"changes", ...ChangeBatch as published...}`: `area`, `changes`, and the
    logSeqs, part numbers and `traceparent` only if the batch had them. Changes are deltas (update,
    created, deleted, stub; `docs/resources.md`) and pass through exactly as published (the batch
    is re-serialised from the `ChangeBatch` record, whose annotations omit nulls).
  - A reload batch becomes `{"type":"reload","area","logSeq","traceparent"}`; the registry first
    reads the area again from world (below).
  - The token's `exp` has 1 s resolution; a token whose `exp` is already past is rejected even
    though Nimbus tolerates 60 s of skew.
  - The checksum every `checksum-interval` goes to every socket for every subscribed area (one frame
    per area), computed from the in-memory version map (no call to world).

## Design
- `SubscriptionsHandler` (`TextWebSocketHandler`): the state machine above. Frames of a session are
  handled sequentially on one container thread. Per-socket state lives in `Client`.
- `Client`: wraps the session in `ConcurrentWebSocketSessionDecorator` (one writer, 1 MB buffer,
  10 s send limit, overflow strategy TERMINATE: a slow client is closed). Holds the user's raw JWT,
  the auth-timeout and expiry timers (Boot's `TaskScheduler`, pool size 4).
- `AreaRegistry`: `area id -> Area{clients, versions, loaded, pending, retired}`; each `Area` is its
  own monitor. First subscriber loads the versions from world (`WorldClient`: `/versions`, then the area) outside the monitor,
  under a separate `loadLock`, so concurrent first subscribers share one load. A socket is added and
  sent `subscribed`+`checksum` in one critical section; a batch's versions are merged and its
  recipients snapshotted in one critical section: a socket sees every batch applied after its
  checksum and none applied before. Versions are merged with max (a batch that arrived while loading
  and the loaded value). A `deleted` change **removes** the id from the map (a no-op if absent);
  `created`, updates and stubs add/update it with max, so the checksum covers exactly what exists.
  A `reload`, a stub of the area itself, or a batch whose `fromLogSeq` is past the area's known
  `logSeq` (this service missed a NOTIFY) makes the registry read the area again (versions and
  `activeEntity`) with the first subscriber's token, synchronously on the LISTEN thread (both are
  rare), and replace the map; a failed read keeps the old map and logs. Known gap: a delete that arrives while the first load is
  in flight can be undone by the older world answer (no tombstone). The area is dropped (`retired`) when its last socket leaves, disconnects,
  or a load fails with no one else waiting.
- `logSeq` (`docs/log.md`, "Following the log live"): the `changes` frame is the batch as is, so it
  carries `logSeq` only when the batch has one. `Area.logSeq` holds the highest seen in any batch
  (max, never lowered; a batch without one, or a split batch repeating a value, changes nothing;
  guarded by the area monitor). Every `checksum` includes it, the one after `subscribed` too, and
  leaves the key out until one is known. It lives and dies with the `Area` like the version map
  (a retired area forgets it). It is loaded from world's `/versions`, which says 0 for an empty log.
- Turns (#148, `docs/websocket.md` "turn"): `Area.activeEntity` is the area's `activeEntity` (null: nobody),
  from the load (`WorldClient.fetch`) and from the area's updates (`set.activeEntity`, or `unset`
  naming it), kept only if its area version is no older (`upVersion`). Pending entities get
  no frame. `TurnFrames` builds the frame when the active entity's controller is the socket: `Client.service` (the JWT's `kind=service`)
  picks `{"kind":"service","principal"}` over `{"kind":"user","sub"}`, matched to `Client.user`
  (the `sub`). `version` comes from the area's version map. Sent after `subscribed`+`checksum` (same
  critical section), after every `changes` or `reload` frame of the area, and after each
  periodic checksum.
- Turn feed (#170, `TurnFeed`): `subscribe{turns:true}` answers `subscribed{turns:true}` and adds
  the socket to the feeds. On its own thread (`subscriptions-turn-feed`, never the LISTEN thread)
  it lists every area (`WorldClient.areas`: all pages of `?type=area&size=200`, the socket's JWT)
  and subscribes the socket, through `AreaRegistry.subscribe`, to each whose `activeEntity` it
  controls (`TurnFrames.controls`). A second LISTEN listener, after the registry's, queues the same
  join for every batch whose area update sets an active entity a feed socket controls and the
  socket isn't subscribed to. A reload (a crossing: a new level's active entity is in no delta) or a
  stub of the area instead queues, per feed socket not in the area, a read of the area with its JWT
  (`WorldClient.area`) and a join if it is up there. So the socket gets `subscribed{area}` + `checksum` + `turn` like any
  subscriber, and every later frame of that area. Joined areas (`Client.fedAreas`) stay until
  `unsubscribe{turns:true}` (which leaves them and answers `unsubscribed{turns:true}`) or the close.
  A failed list or join is an `error` frame; the next batch for the area tries again.
- `WorldClient`: `RestClient` (from Boot's builder, so trace headers propagate) calling
  `GET {world-url}/api/world/resources/{area}/versions` (everything under the area, items included,
  and the log head) and `GET .../{area}` (for `activeEntity`) with
  `Authorization: Bearer <the subscriber's JWT>`.
- Fan-out: one listener registered at startup (`WebSocketConfig.Wiring`) -> `AreaRegistry.onBatch`
  inside a child span `subscriptions.fanout` continued from the batch's `traceparent`
  (`MicrometerTracing.inChildSpan`). Batches for areas nobody watches are dropped. Subscribe loads
  run in span `subscriptions.load-area`.
- Checksum and WebSocket ping are `@Scheduled` (`checksum-interval`, `ping-interval`).
- `ListenerHealthIndicator` (`changeListener`) calls `ChangeSubscriber.awaitListening(ZERO)`, which
  is a non-blocking read of `PostgresChangeSubscriber.isListening()` (the library's lifecycle
  wrapper hides the concrete class, so `isListening()` isn't reachable directly).

## Known limits
- No per-user authorization: any authenticated user may subscribe to any area. The first
  subscriber's world call (with its JWT) is the only check; later subscribers share the map.
- NOTIFY isn't durable: a reconnect of the LISTEN connection loses batches. Clients see the gap at
  the next batch (the logSeq chain) or the next checksum and resync from world. This service follows
  the chain too and reads the area again on a gap; a missed *last* batch leaves its map off until the
  area's next batch. There is no reconnect hook in the library to resync sooner.
- Fan-out and subscribe sends happen on the LISTEN thread / container threads; a very large
  number of sockets per area would want a dedicated sender pool.
- A subscribe's world call blocks that socket's thread (connect timeout 3 s, read timeout 10 s,
  `world-connect-timeout` and `world-read-timeout`). The world answer's shape is trusted
  (a `/versions` body without `versions` gives an empty map).
- Batches with no area, no changes (unless a reload), or a change without id/type are logged and dropped.
- The first periodic checksum arrives 0..30 s after subscribe (fixed delay from process start).
- Single instance: sockets, maps and versions are per replica; that's fine because every replica
  LISTENs to everything.

## Configuration
| env var | default | meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scarcity` | only for the LISTEN connection and the `db` health check |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `scarcity` / `scarcity` | k8s: secret `postgres-secrets` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | k8s: `http://auth:8080/api/auth/.well-known/jwks.json` |
| `WORLD_URL` | `http://localhost:8080` | k8s: `http://world:8080` |
| `SUBSCRIPTIONS_CHECKSUM_INTERVAL` | `30s` | the periodic checksum, which also repeats the turn; end-to-end tests of controllers set it to `1s` |
| `SPRING_PROFILES_ACTIVE` | | `k8s`: ECS JSON logs, graceful shutdown, pool 5 |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | | as world |

Properties (`scarcity.subscriptions.*`): `world-url`, `jwks-uri`, `jwt-issuer` (`scarcity-auth`),
`auth-timeout` (5s), `checksum-interval` (30s), `ping-interval` (30s), `max-message-bytes` (65536),
`send-buffer-bytes` (1048576), `send-time-limit` (10s), `world-connect-timeout` (3s),
`world-read-timeout` (10s). `scarcity.resource-changes.listen=true`.
No volumes; read-only-fs friendly. Resources: JVM heap 75% of the limit; the sockets are cheap.

## Run locally
```
docker run -d --name subs-pg -p 5432:5432 -e POSTGRES_DB=scarcity -e POSTGRES_USER=scarcity -e POSTGRES_PASSWORD=scarcity postgres:18.6
SERVER_PORT=8083 WORLD_URL=http://localhost:8082 AUTH_JWKS_URI=http://localhost:8080/api/auth/.well-known/jwks.json \
  mvn -B -f ../../pom.xml -pl services/subscriptions -am spring-boot:run
```
Then connect a WebSocket client to `ws://localhost:8083/api/subscriptions/ws` and send
`{"type":"auth","token":"<jwt from /api/auth/login>"}`.

## Tests
`mvn -B -f ../../pom.xml -pl services/subscriptions -am verify` (Testcontainers postgres:18.6, needs
Docker). `SubscriptionsIntegrationTest` boots the service with `listen=true` and drives it with a
real `java.net.http.WebSocket` client, an in-test JWKS server, a stub world (`TestSupport.StubWorld`,
a JDK `HttpServer`) and the library's `PostgresChangePublisher` in committed transactions. It
covers boot/readiness/no `/resources`, 4400/4401 paths, ready, expiry, subscribe+checksum,
idempotence, errors, fan-out isolation between areas, checksums after batches, a reload, unsubscribe,
ping/pong, unknown types, closed sockets, oversize frame, server WebSocket pings, turns, and the
turn feed (joins only the areas the socket is up in, a later area from its batch or its reload,
unsubscribe). Test properties shorten `auth-timeout` (500ms), `checksum-interval` (2s) and `ping-interval` (1s).

## Image
`services/subscriptions/Dockerfile` (context = repo root; copies the poms of root, resource-model,
auth, world, subscriptions; builds `-pl services/subscriptions -am`). Verified: starts against a
throwaway Postgres with no auth/world running; `:8081/actuator/health/readiness` -> 200 UP.
