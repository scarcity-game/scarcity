# AGENTS.md: libs/resource-model

Java library (package `com.pessimisticit.scarcity.resourcemodel`) every service uses
for resources: envelope, version rule, Postgres store, change notifications over
LISTEN/NOTIFY, and the generic read-only resource API. Design: root AGENTS.md ->
"Resources". First user: `services/world`; consumer of notifications:
`services/subscriptions`. Services never call `pg_notify`/`LISTEN` themselves,
never do version arithmetic and don't hand-write the REST controller.

## Wiring in a service
1. Depend on `com.pessimistic-it.scarcity:resource-model` (add `libs/resource-model` to the
   service's `paths` in modules.yaml). `spring-boot-starter-web` and `micrometer-tracing`
   are *optional* here: bring your own.
2. Copy `src/main/resources/db/resource-model/resources.sql` (idempotent) into the
   service's own Flyway migration. Table `resources` is unqualified, so it lives in the
   service's schema (datasource `search_path`).
3. Auto-configuration (`AutoConfiguration.imports`) creates, all `@ConditionalOnMissingBean`:
   `JdbcResourceStore`, `PostgresChangePublisher` (with a `TraceparentSource` when a
   Micrometer `Tracer` bean exists), `ResourceController` (servlet web app + a
   `ResourceStore` bean), and `PostgresChangeSubscriber` only if
   `scarcity.resource-changes.listen=true`.
4. Write inside the service's own `@Transactional`; the store never opens a
   transaction (not enforced; follow-up). Publish in the same transaction.

## Properties
- `scarcity.resource-api.prefix` (default `/resources`; world uses `/api/world/resources`)
- `scarcity.resource-changes.listen` (default false)
- `scarcity.resource-changes.url|username|password`: optional listen connection. If url
  is unset and the DataSource is Hikari, its jdbcUrl/user/password are used; otherwise one
  connection is taken from the DataSource and never returned.

## Public API
- `Resource(id, type, version, children, state)` record; JSON keys in that order;
  `ResourceType` GAME/AREA/ENTITY/ITEM serialised lower case. `Resource.newResource(type, state)`.
  `ResourceIds`: `newId(type)`, `typeOf(id)` (IllegalArgumentException if malformed), `isValid`, `matches(type,id)`.
- `ResourceStore`: `get`, `exists`, `areaOf`, `list(type, filter, page, size)`,
  `children(parentId, type|null, page, size)`, `create(resource, parentId|null)`,
  `updateState(id, ObjectNode)`, `delete(id)`. `Page{items,page,size,total}`.
  Writes return `WriteResult(id, type, version, areaId, parentId, parentVersion)`: enough to
  build a `ChangeBatch` for the resource and its parent.
  - Version rule: create -> 1; updateState `UPDATE ... SET version = version+1 RETURNING`;
    child create/delete bumps the parent once; child updateState doesn't touch the parent.
  - delete: `WriteResult.version` is the deleted resource's last version; throws
    IllegalStateException if it has children, `ResourceNotFoundException` if missing.
  - Children are derived from `parent_id`, ordered `(created_at, id)`.
  - `area_id`: an area's own id; entity/item: copied from the parent at create; null for games.
    Not an FK. Exposed as `WriteResult.areaId` / `areaOf(id)`.
  - Filter: equality on `state->>key`, key and value bound parameters, key must match
    `[A-Za-z0-9_.-]{1,64}`. Store: page >= 0, size 1..200 else IllegalArgumentException.
- `ChangeBatch(area, changes[Change(id,type,version,patch)], traceparent)`; null patch and
  traceparent omitted in JSON. Unknown JSON fields are ignored when decoding.
- `ChangePublisher.publish(batch)` (`PostgresChangePublisher`): channel **`resource_changes`**
  (one channel; subscribers filter by area). Payload cap 7900 UTF-8 bytes: batches are
  split greedily; a single change that doesn't fit is sent without `patch`.
- `ChangeSubscriber` (`PostgresChangeSubscriber`): `addListener(Consumer<ChangeBatch>)`,
  `start`, `stop`, `awaitListening(Duration)`. Dedicated connection, daemon thread,
  `SELECT 1` keepalive every 10 s, reconnect backoff 250 ms..10 s. Notifications sent while
  disconnected are lost (accepted; consumers use the version checks). Listener exceptions are logged.
- Tracing: `MicrometerTracing.source(tracer)` -> `TraceparentSource` (`00-<traceId>-<spanId>-01`);
  `MicrometerTracing.inChildSpan(tracer, batch, name, Callable)` continues a trace on the
  consumer side. Only that class touches `io.micrometer.tracing`.
- Controller (`GET {prefix}`, `{prefix}/{id}/children`, `{prefix}/{id}`): list requires `type`
  (400 otherwise); `page` default 0, `size` default 50, size > 200 is clamped to 200, size < 1
  or bad page is 400; unknown query params are the filter (`type`,`page`,`size` reserved);
  `{id}` ETag `"<version>"`, `If-None-Match` (also `W/`) -> 304; 404 missing (also
  children of a missing parent); 400 malformed id. Errors `{"error": "..."}` (only those
  the controller raises itself; Spring's own 405/406 keep Spring's format). No JWT checks.

## Build / test
`mvn -B -f ../../pom.xml -pl libs/resource-model -am verify` (Testcontainers postgres:18.6,
needs Docker). Tests: JSON shape, store, notifications (raw LISTEN payloads, split,
reconnect via `pg_terminate_backend`), controller, tracing, edge cases.

## Known limits / follow-ups
- No reconnect hook on the subscriber for consumers to resync after a gap.
- Transaction use isn't enforced (`Propagation.MANDATORY` would).
- Not tested on a classpath without micrometer-tracing in CI (checked manually only).
