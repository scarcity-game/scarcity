# AGENTS.md: libs/world-engine

The advance, in memory (package `com.pessimisticit.scarcity.world.engine`).
Contract: `docs/actions.md` ("Game time", "Actions", "Intents"),
`docs/action-types.md` ("Behaviours", "Effects") and `docs/log.md`. Part of #103; the split is in
root `AGENTS.md`, under the module table.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope; the enforcer rule `pure-engine` in `pom.xml` bans Spring,
JDBC and `resource-model`. No wall clock: the caller passes everything in.
Tests need no Docker. Lint applies with no baseline entries.

**Rules are objects.** Events run through `Catalogue.start`/`resume`,
`BoundAction`, `EffectCall.run` and `Behaviour.next`/`options`. Nothing
here compares or switches on an action, effect, behaviour or controller
name (`Ticker.doTick` in `services/world` was the anti-example). Effects set
off steps through `Work` (`RunEffect`, `StartAction`), which call back into
`Advance`.

## API
| type | what |
|---|---|
| `Engine.advance(snapshot, intent, rng, nextSeq, catalogue)` | one advance; throws `IntentRejected` or `RunawayAdvance` |
| `AreaSnapshot(area, entities, roots)` | the model's `Area` and `Entity`s with their versions; `roots`: the log root of each `action.seq` in it (missing: pruned, the seq is its own root). An action that ends before the area's `time` is rejected |
| `Intent(entity, action, by, ifVersion, reported)` | `by` is the `Controller` the log records; whether it may control the entity is the caller's check (403, #112) |
| `IntentRejected.Reason` | `UNKNOWN_ENTITY` 404, `NOT_CONTROLLED` 403, `STALE` 409, `BUSY` 409, `INVALID` 422 (the catalogue's message). Checked in that order; nothing happened |
| `RunawayAdvance` | more than `Engine.MAX_STEPS` (100,000) events and effects; the caller rolls back |
| `AdvanceResult(area, areaVersion, writes, log, blocked)` | `area`: the new state (its `time`, `up` and `pending`); `areaVersion`: snapshot + 1 (every advance writes the area); `writes`: one `Write(id, op, version, state)` per entity; `created()`/`destroyed()`: the child-list change; `blocked`: entities whose behaviour couldn't start, with the reason (the caller warns once, #85) |
| `LogEntry` | the columns (`seq`, `time`, `kind`, `cause`, `root`, `resource`) as components, `bodyJson()` as docs/log.md has it; the caller adds `area` and `wallTime`. `LogEntry.snapshot(seq, snapshot)` builds the snapshot entry the caller writes first when one is due (it then passes `seq + 1`) |
| `LogChange`, `LoggedDraw`, `LoggedEffect` | items of an entry |

## The advance
1. `Admission` checks the intent. Then the `intent` entry, then the
   action's start at the area's `time` (cause: the intent).
2. Events: an action's `end`, or "now" for an idle entity with a behaviour
   that isn't blocked. The earliest runs first, ties by id. An event runs
   whole before the next one: the end's change (`end` timing), its entry,
   its effects depth first, then the behaviour's next start at that `end`.
3. **Stop rule:** once a controlled entity is idle, or none is left, the
   advance finishes every event at that time and stops. Later events stay in
   the state for the next advance. The area's `time` is the time of the last
   event processed, which is the last entry's time (docs/log.md).
4. A behaviour first checks that every option's type is listed
   (`Catalogue.listed`; no draw if one isn't), then draws, then runs the
   pick through `Catalogue.start`. Any failure blocks the entity for the rest
   of the advance and is in `blocked`. A pick that fails the start rules
   after its draw starts nothing, and its draw isn't logged.
5. Created ids are version 4 UUIDs from two `rng.nextLong()`s, as
   `EffectRunner.newId` did, redrawn if taken.

**Versions are the engine's** (the choice #109 left open). A log change
carries the version the advance writes: snapshot + 1 for a changed entity,
1 for a created one, null when none is written (created or changed, then
destroyed, in this advance). So entries are final when they leave the
engine. The caller must write exactly those versions: it holds the area's
lock (the log head), and a write that finds another version fails the
transaction.

An effect that starts an action on a missing or busy entity or against a
start rule, and an action in progress that no longer decodes, are bugs in
the rules or the data, not a bad intent: `IllegalStateException` (never
`InvalidAction`, which the caller maps to 422), and the caller rolls back.

## Tests
`Store` does in memory what the caller will do (apply each result as the
next snapshot, keep the log and roots) and checks `Invariants` after every
advance: gapless seq from `nextSeq`, time never decreasing, cause/root tree
(roots across advances included), one write per entity covered by an entry
with its version, every logged version a written one, nothing due left, a
controlled entity idle. `ScriptedRandom` fails on an unscripted draw, so a
test also shows which draws happened.

- `AdvanceTest`: stop rule, step result, chaining at `end`, random NPC
  acting during the controlled action, pending events, ties at the stop
  time, actions without a seq or with a pruned start, a blocked behaviour, a
  second controlled entity.
- `TurnsTest`: `up` and `pending` after an advance: one controller up from
  its action's end; two idle at once taking turns by id, the pending one
  refused (`NOT_UP`); an earlier `since` before a lower id; a controlled
  entity destroyed in the advance dropping out; nobody recorded yet (queued
  from the stop time). `AdvanceTest.eventsAtTheSameTimeResolveInOrderOfId`
  pins the tie order. `Admission` computes the queue from the snapshot (not
  the stored `up`) and admits only its first.
- `RejectionTest`: each reason. `ThrowTreeTest`: the docs/actions.md example
  and the docs/log.md tree, the pebble only in the log, the rubble's decay a
  later advance under the throw's root, a miss that doesn't break.
- `EffectEngineTest` (test rules beside the standard ones): destroy cancels,
  changed-then-destroyed has no version, create starts an action, the cap.
- `LongRunTest`: 2,000 time units, random intents and a random rat, run
  twice for the same log. `LogEntryTest`: body shapes, the snapshot entry.
