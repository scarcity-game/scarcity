# AGENTS.md: libs/resource-model

Java library (package `com.pessimisticit.scarcity.resourcemodel`) every service uses
for resources: envelope, version rule, Postgres store, change notifications over
LISTEN/NOTIFY, and the generic read-only resource API. Contract: `docs/resources.md`
(rationale in `docs/decisions.md`). First user: `services/world`; consumer of notifications:
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
  `list(type, gameId|null, filter, page, size)` and `list(type, gameId|null, filter, has, page, size)` (`has`: keys that must be present non-null; limited to a game by nested `parent_id` lookups: game, its
  areas, their entities; bad game id -> IllegalArgumentException, unknown game -> empty page),
  `children(parentId, type|null, page, size)`, `create(resource, parentId|null)`,
  `updateState(id, ObjectNode)`, `delete(id)`. `Page{items,page,size,total}`.
  Writes return `WriteResult(id, type, version, areaId, parentId, parentVersion)`: enough to
  build a `ChangeBatch` for the resource and its parent.
  - Version rule: create -> 1; updateState `UPDATE ... SET version = version+1 RETURNING`;
    child create/delete bumps the parent once; child updateState doesn't touch the parent.
  - `applyChildren(parentId, creates, deletes, newState|null)` returns
    `ChildrenWrite(parentId, parentVersion, children, created, deleted)`: one tick's creates, deletes and
    optional parent state write with **one** parent bump (version+1). Use it instead of looping
    `create`/`delete` when a tick changes several children. Order: deletes first, then creates in the
    order given; `children` is the parent's new child list (creation order). `created`/`deleted` are
    `WriteResult`s (created version 1, deleted last version) whose `parentVersion` is the new parent
    version, so each can feed a `ChangeBatch`. Parent is row-locked first. Everything is validated before
    any write: delete id not a child of that parent, duplicate id, an id both created and deleted
    (rejected, not a no-op) and an empty call (no creates, deletes or state) throw
    IllegalArgumentException; missing parent `ResourceNotFoundException`; deleting a child that has
    children IllegalStateException. Existing `create`/`delete`/`updateState` are unchanged.
  - delete: `WriteResult.version` is the deleted resource's last version; throws
    IllegalStateException if it has children, `ResourceNotFoundException` if missing.
  - Children are derived from `parent_id`, ordered `(created_at, id)`.
  - `area_id`: an area's own id; entity/item: copied from the parent at create; null for games.
    Not an FK. Exposed as `WriteResult.areaId` / `areaOf(id)`.
  - Filter: equality on `state->>key` (the resource's own keys), key and value bound parameters, key must match
    `[A-Za-z0-9_.-]{1,64}`. Store: page >= 0, size 1..200 else IllegalArgumentException.
- `ChangeBatch(area, reload, changes, fromLogSeq, logSeq, part, parts, traceparent)` (#186): deltas,
  never a whole resource. Build with `ChangeBatch.of(area, changes, fromLogSeq, logSeq, tp)` or
  `ChangeBatch.reload(area, logSeq, tp)`; `reloading()` reads it (`reload` is `true` or null, never false,
  because Jackson 3 refuses a missing primitive). JSON order area, reload, changes, fromLogSeq, logSeq, part,
  parts, traceparent; nulls and an empty change list omitted. A `Change(ObjectNode json)` is `@JsonValue`;
  `kind()` is UPDATE, CREATED, DELETED or STUB:
  - `Change.update(before, after, childrenAdded, childrenRemoved)` diffs top-level own keys (not
    `resourceKind`/`id`/`version`/`children`), number-aware, null = absent; a changed value goes whole in
    `set`, a gone one in `unset`. The child list is never diffed: the caller passes what it added and removed.
    An update with nothing in it carries `"set":{}`, so it never reads as a stub.
  - `Change.created(id, kind, version)`, `Change.deleted(id, kind)`, `change.stub()` (updates only).
  - `set()`, `unset()`, `childrenAdded()`, `childrenRemoved()` read it; `applyTo(json)` applies an update,
    as a client does (tests use it to check the diff).
- The publisher sends a reload as is and skips an empty batch. A batch over the limit is split into parts
  numbered `part`/`parts` (1-based), each with the batch's area, logSeqs and traceparent; an update that
  doesn't fit alone becomes a stub. A batch that fits carries no part numbers.
- `VersionChecksum`: `term(id, version)` = low32(id) × version in `int` (wraps mod 2³²), `of(map)`, `hex`.
  Test vectors in `src/test/resources/golden/version-checksums.json`, shared with `clients/web`.
- `ResourceStore.versions(id)`: id → version of the resource and everything under it (recursive on
  `parent_id`), the resource first. `GET {prefix}/{id}/versions` serves it as
  `{id, versions, checksum, logSeq}` in one REPEATABLE READ transaction; `logSeq` comes from a
  `LogSeqSource` bean if the service has one (world-db), else is left out.
  Unknown JSON fields are ignored when decoding.
- `ChangePublisher.publish(batch)` (`PostgresChangePublisher`): channel **`resource_changes`**
  (one channel; subscribers filter by area). Payload cap 7900 UTF-8 bytes: batches are
  split greedily; a single change that doesn't fit is sent as a stub (subscribers GET it); a deleted change is always small.
- `ChangeSubscriber` (`PostgresChangeSubscriber`): `addListener(Consumer<ChangeBatch>)`,
  `start`, `stop`, `isListening()` (true only while a working LISTEN connection is held;
  false before start, after stop and while reconnecting), `awaitListening(Duration)`. Dedicated connection, daemon thread,
  `SELECT 1` keepalive every 10 s, reconnect backoff 250 ms..10 s. Notifications sent while
  disconnected are lost (accepted; consumers use the version checks). Listener exceptions are logged.
- Tracing: `MicrometerTracing.source(tracer)` -> `TraceparentSource` (`00-<traceId>-<spanId>-01`);
  `MicrometerTracing.inChildSpan(tracer, batch, name, Callable)` continues a trace on the
  consumer side. Only that class touches `io.micrometer.tracing`.
- Controller (`GET {prefix}`, `{prefix}/{id}/children`, `{prefix}/{id}`): list requires `type`
  (400 otherwise); `page` default 0, `size` default 50, size > 200 is clamped to 200, size < 1
  or bad page is 400; unknown query params are the filter (`type`,`page`,`size`,`game`,`has` reserved; `has=<key>` (repeatable) keeps resources whose JSON has that key non-null, counts toward the 16-filter limit, empty is 400; `game=<game id>` scopes the list to one game);
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
