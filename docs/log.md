# The area log: what happened, in order

Owned by the repo agent. Read this if your module writes game state
(`services/world`) or shows history to players (the clients). Reasons are in
[`decisions.md`](decisions.md).

The resources ([`resources.md`](resources.md)) say what is true now. The **area
log** says what happened: every change to a resource in an area is recorded as
a log **entry**, in order, with the exact game time, what caused it, the random
draws behind it and the resulting state. A player can read it, and a client
can replay an area from it without knowing any game rules.

## Rules
- **No write without an entry.** Every write to an area or to anything in it is
  covered by a log entry committed in the same transaction. For every resource
  version written, at least one entry lists that resource and version in its
  `changes`.
- **Append-only.** Entries are never updated. They are only removed by pruning
  (below).
- **`seq`** numbers the entries of one area: integers from 1, +1 per entry, no
  gaps. `seq` is the order in which things happened, including entries at the
  same game time.
- **`time`** is the game time at which the thing happened (an action's `start`
  or `end`, not the time of the tick that processed it). It never decreases as
  `seq` grows.
- **Cause and root.** `cause` is the `seq` of the entry that triggered this
  one, or `null` when nothing in the log did (a behaviour or, later, a player
  chose it). `root` is the `seq` at the top of that chain; an entry without a
  cause is its own root. One action and everything it sets off is one tree.
- **Playback, not re-simulation.** Each entry carries the resulting state of
  what it changed. A client replays by applying `changes` in `seq` order.
- One write per resource per tick still holds (`resources.md`). When several
  entries change a resource in one tick they all carry the one version that
  tick wrote, each with the state as it was after that entry.

## An entry

```json
{
  "seq": 5012,
  "area": "area:00000000-0000-4000-8000-000000000002",
  "time": 1043.0,                            // game time
  "wallTime": "2026-11-12T19:25:09.123Z",    // when it was committed
  "kind": "action-end",
  "cause": 5010,
  "root": 5010,
  "resource": "entity:…0004",                // who or what it is about; null for the area as a whole
  "action": { "type": "orbit-step", "params": {…}, "start": 1042.0, "end": 1043.0, "effect": "end", "seq": 5010 },
  "draws": [],                               // every random draw this entry made
  "changes": [
    { "id": "entity:…0004", "type": "entity", "op": "changed", "version": 812, "state": { … } }
  ]
}
```

`changes[].op` is `changed` (with the full resulting `state`) or `snapshot`
(below). `created`, `destroyed` and a parent's changed child list come with
the effects that create and destroy resources.

### Kinds
| kind | when | cause | carries |
|---|---|---|---|
| `snapshot` | the log of an area begins; then once per snapshot interval | `null` | `changes`: the area and every resource in it, `op: "snapshot"`, with current version and full state. No resource is written. |
| `clock-anchored` | the area's clock anchor is written | `null` | `reason`: `first-tick` or `pull-back`; `changes`: the area |
| `action-start` | an action starts | `null` | `action`; `draws` (the behaviour's pick, if it was random); `changes`: the resource, with the new `action` in its state and, for a `start` effect, the effect |
| `action-end` | an action ends | the `seq` of its `action-start` | `action`; `changes`: the resource, without the `action` and, for an `end` effect, with the effect |

- The `action` in a resource's state gets a `seq` key: the `seq` of its
  `action-start` entry. That is how the end finds its cause, and how a client
  finds the log entry for what it sees. An action that started before the log
  existed has none; its `action-end` has `cause: null`.
- A draw in `draws` is the same record as in `resources.md` ("Behaviours"),
  plus `"for"` saying what was drawn (`"behaviour"`).
- When an action ends and the behaviour starts the next one at the same game
  time, that is two entries: the `action-end`, then the `action-start`. Both
  list the resource; the end's state has no `action`.

## Snapshots and pruning
- A log always starts with a `snapshot`, so the log alone is enough to
  rebuild the area at any later point: take the latest snapshot at or before
  the point, then apply `changes` in order.
- A tick writes a snapshot when the area has no log yet, and when the last
  snapshot is older than the snapshot interval (default 60 minutes of wall
  time). This is the one case where a tick with no due events writes
  something.
- **Retention** (default 24 hours of wall time): entries before the newest
  snapshot that is older than the retention are deleted. `seq` keeps counting;
  it never restarts.

## Reading the log
`GET /api/world/log?area=<area id>&after=<seq>&limit=<n>&kind=<kind>` (JWT
required). `after` is exclusive and defaults to 0; `limit` defaults to 100,
maximum 500; `kind` is optional. Entries come in ascending `seq`:

```json
{ "entries": [ … ], "first": 4000, "last": 5012 }
```

`first` and `last` are the lowest and highest `seq` the area's log holds now
(both `null` for an empty log). An unknown area gives an empty log.

The log is not a resource: it has no version and no `ETag`, and it isn't
published over `LISTEN/NOTIFY`. Live clients keep following resources; the log
is for looking back.

## Not yet
- Effects that reach other resources or create and destroy them, each as its
  own entry with a `cause` (the reason `cause` and `root` exist).
- A log for changes to a `game` itself, which is in no area.
- Pushing new entries to clients as they happen.
