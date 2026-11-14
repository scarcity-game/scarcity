# AGENTS.md: libs/world-db

Loads an area into model objects and commits an advance
(package `com.pessimisticit.scarcity.world.db`). Contract:
`docs/resources.md` (versions, change batches) and `docs/log.md` (the
log, snapshots, pruning, reading). Part of #103; the split is in root
`AGENTS.md`, under the module table. `services/world` wires it in #112.

Depends on `libs/world-engine` (the advance's types) and
`libs/resource-model` (the store and the publisher). No Spring beans: every
class takes its collaborators in its constructor, and every method joins
the caller's transaction and starts none. No `pg_notify` or `LISTEN`
here: batches go through `ChangePublisher` (root rule). Lint applies with
no baseline entries.

## API
| type | what |
|---|---|
| `AreaLock(jdbc, settings).begin(areaId, now)` | inserts the area's `area_log_head` row if missing and locks it `FOR UPDATE` until the transaction ends; this serialises intents on one area. Returns `AreaHead` |
| `AreaHead(areaId, lastSeq, lastSnapshotAt, now, snapshotDue)` | `nextSeq()` is the seq to give the engine: after the snapshot entry when one is due (`snapshotSeq()` = `lastSeq + 1`) |
| `AreaRepository(store, publisher, jdbc, settings)` | `load(areaId)` and `commit(head, snapshot, result, traceparent)` |
| `AreaLogReader(jdbc).read(areaId, after, limit, kind)` | `{"entries", "first", "last"}` (docs/log.md, "Reading the log"); `limit` 1..`MAX_LIMIT` (500) or `IllegalArgumentException`; the service checks and clamps the request's parameters |
| `LogSettings(snapshotInterval, retention)` | `defaults()`: 60 minutes, 24 hours |

One intent, in one transaction (what #112 does):

    AreaHead head = lock.begin(areaId, now);
    AreaSnapshot snapshot = repo.load(areaId);
    AdvanceResult result = Engine.advance(snapshot, intent, rng, head.nextSeq(), catalogue);
    repo.commit(head, snapshot, result, traceparent);

## Load
The area (`ResourceNotFoundException` if missing or not an area), its
entities through `children(area, ENTITY)` in pages of `MAX_SIZE`, each
state read with `WorldJson` (a state that doesn't read throws
`WorldJsonException`), and the `root` of every `action.seq` the entities
hold, from `area_log`. A pruned seq is left out, and the engine treats it as
its own root.

## Commit
In this order, inside the caller's transaction:
1. Check that the result follows the head and the snapshot: the same area,
   the log starting at `head.nextSeq()`, and the area version as the snapshot's + 1.
2. Write the area **once**: `applyChildren(area, created, destroyed, state)`
   when the child list changed, otherwise `updateState`. Then each changed
   entity by id with `updateState`. A resource created and destroyed within
   the advance isn't in `writes` and is never stored.
3. Publish one `ChangeBatch`. It holds the area first (with `children` when
   the list changed), then the entities in the result's order (changed by
   id, created at version 1, destroyed as `deleted`). Each `patch` is the full
   state, `logSeq` is `result.lastSeq()`, and the caller passes `traceparent`.
4. Write the entries: the snapshot first when one was due (`LogEntry.snapshot`,
   with the versions loaded), then the result's. Each row gets `wallTime`
   = `head.now()`, and its body is `LogEntry.bodyJson()`.
5. Write the head: `last_seq` from the result, and `last_snapshot_at` = now
   after a snapshot. Prune after a snapshot.

**The versions are the engine's** (world-engine AGENTS.md). If the store
writes a version the engine didn't expect, `IllegalStateException` fails
the transaction: someone wrote the area without its lock.

An advance always writes the area (its `time`), so every commit publishes
one batch and writes at least the `intent` entry. There are no empty
advances.

## Tables
The SQL is in `services/world/src/main/resources/db/migration`, and released
files are never edited:
- `resources` (`V1`, a copy of resource-model's): through `ResourceStore` only.
- `area_log(area_id, seq, game_time, wall_time, kind, cause, root, resource, body jsonb)`,
  PK `(area_id, seq)`, with indexes `(area_id, kind, seq)` and the snapshots'
  `(area_id, wall_time)` (`V2`).
- `area_log_head(area_id PK, last_seq, last_snapshot_at)` (`V2`).

The tests run Flyway on those files, so `modules.yaml` lists that directory
in this module's `paths`.

## Tests (Testcontainers)
`DbTest` starts one Postgres (prod's version), migrates it, and builds the
classes by hand. There's no Spring context, and commits are real ones,
because NOTIFY is delivered only on commit and the lock is between
transactions. Batches are received with resource-model's
`PostgresChangeSubscriber`, as subscriptions receives them. Each test seeds
its own `Room` with fresh ids. The advances are real engine runs
(`Catalogue.standard()`), and `ScriptedRandom` scripts the throw's draws.

- `CommitTest`:
  - one area version per advance, with creates and destroys;
  - batch order and contents, and `logSeq` equal to the log's `last`;
  - patches equal to the stored states;
  - a pebble created and destroyed in one advance is never stored and logs no version;
  - every logged version is the written one;
  - the stored entry shape, with gapless seq;
  - a rollback leaves no rows, no head and no batch;
  - a write without the lock fails the commit;
  - a result that doesn't follow the head is refused.
- `LockTest`: a second `begin` waits for the first commit and continues its
  seq with no gaps; the first `begin` creates the head.
- `SnapshotTest`:
  - the first advance starts the log with a snapshot at the loaded versions;
  - one snapshot per interval;
  - pruning before the newest snapshot older than the retention;
  - `load` reads the roots, and a pruned one is missing.
- `ReaderTest`: pages, `first`/`last`, the kind filter, an unknown area,
  the limit.
