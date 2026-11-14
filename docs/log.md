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
  or `end`). It never decreases as `seq` grows.
- **Cause and root.** `cause` is the `seq` of the entry that triggered this
  one, or `null` when nothing in the log did (an `intent`, a behaviour's
  start, a snapshot). `root` is the `seq` at the top of that chain; an entry without a
  cause is its own root. One action and everything it sets off is one tree.
- **Playback, not re-simulation.** Each entry carries the resulting state of
  what it changed. A client replays by applying `changes` in `seq` order.
- One write per resource per advance still holds (`actions.md`). When several
  entries change a resource in one advance they all carry the one version
  that advance wrote, each with the state as it was after that entry.

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
  "action": { "type": "step", "params": {…}, "start": 1042.0, "end": 1043.0, "effect": "end", "seq": 5010 },
  "draws": [],                               // every random draw this entry made
  "changes": [
    { "id": "entity:…0004", "type": "entity", "op": "changed", "version": 812, "state": { … } }
  ]
}
```

`changes[].op` is one of:
- `changed`: `version` and the full resulting `state`;
- `created`: `version`, `state` and `parent` (the resource it was added to);
- `destroyed`: nothing else;
- `snapshot` (below).

A parent's child list is not logged separately: replay adds and removes
children from `created` and `destroyed`. So the rule "an entry for every
version written" has one exception: a version that only changed a child list
is covered by the entry that created or destroyed the child. A resource
created and destroyed within one advance is never stored; its `created` and
`changed` entries carry `version: null`. The same goes for an existing
resource that is changed and then destroyed in one advance: no version of it
is written, so its `changed` entries from that advance carry `version: null`.

### Kinds
| kind | when | cause | carries |
|---|---|---|---|
| `snapshot` | the log of an area begins; then once per snapshot interval | `null` | `changes`: the area and every resource in it, `op: "snapshot"`, with current version and full state. No resource is written. |
| `intent` | a controller's intent is accepted (`actions.md`, "Intents") | `null` | `resource`: the entity; `by`: `{"kind":"service","principal"}` or `{"kind":"user","sub"}`; `intent`: `{"type","params"}`; `reported` (if sent): verbatim, not checked by the server; `changes`: none |
| `effect` | an effect happens (`actions.md`, "Effects") | the entry of the action start, action end or effect that triggered it | `effect`: `{"type","params"}`; `result` (if the type has one); `draws`; `changes` |
| `action-cancelled` | a resource with an action in progress is destroyed | the `destroy` effect's entry | `action` |
| `action-start` | an action starts | `null` (a behaviour's pick), the `intent` entry, or the entry of the effect that started it | `action`; `draws` (the behaviour's pick, if it was random); `changes`: the resource, with the new `action` in its state and, for a `start` effect, the effect |
| `action-end` | an action ends | the `seq` of its `action-start` | `action`; `changes`: the resource, without the `action` and, for an `end` effect, with the effect |

- The `action` in a resource's state gets a `seq` key: the `seq` of its
  `action-start` entry. That is how the end finds its cause, and how a client
  finds the log entry for what it sees. An action that started before the log
  existed has none; its `action-end` has `cause: null`.
- A draw in `draws` is the same record as in `actions.md`, plus `"for"`
  saying what was drawn (`"behaviour"`, `"scatter"`, `"damage"`, `"break"`).
  A controller's own pick is not a draw of the game: if it reports one, it
  sits in the `intent` entry's `reported`, never in `draws`.
- Every advance has exactly one `intent` entry (after the `snapshot`, if
  one is due), so the log of a controlled area reads as a list of decisions,
  each followed by everything it led to.
- `root` is inherited down the chain: everything a `throw` sets off, down to
  the rubble's decay twenty time units later, has the throw's `intent` as its
  root (later advances included: an action's end inherits the root of its
  start).

  ```
  intent throw (orbiter, by orbital)
  └─ action-start throw (orbiter)
     └─ effect line-attack
        └─ effect create (pebble)
           └─ action-start fly (pebble)
              └─ action-end fly
                 ├─ effect damage (pillar)
                 └─ effect break (pebble)
                    ├─ effect destroy (pebble)
                    └─ effect create (rubble)
                       └─ action-start decay (rubble)
                          └─ action-end decay
                             └─ effect destroy (rubble)
  ```
- When an action ends and the behaviour starts the next one at the same game
  time, that is two entries: the `action-end`, then the `action-start`. Both
  list the resource; the end's state has no `action`. For a controlled
  resource the advance stops after the `action-end`; the next `intent` and
  its `action-start` come with the next advance.

## Snapshots and pruning
- A log always starts with a `snapshot`, so the log alone is enough to
  rebuild the area at any later point: take the latest snapshot at or before
  the point, then apply `changes` in order.
- An advance writes a snapshot first when the area has no log yet, and when
  the last snapshot is older than the snapshot interval (default 60 minutes
  of wall time). An area that doesn't advance writes no snapshots; its log
  simply stays as it is.
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

The log is not a resource: it has no version and no `ETag`, and its entries
are not published over `LISTEN/NOTIFY`.

### Following the log live
Entries are pulled, never pushed; what is pushed is how far the log goes.
- `world` sets `logSeq` on every change batch it publishes (`resources.md`):
  the `seq` of the last entry the advance wrote.
- `subscriptions` passes it on in the `changes` message and puts the highest
  `logSeq` it has seen for the area in every `digest` (`websocket.md`).
- A client that shows the log remembers the last `seq` it holds. When a
  message carries a higher `logSeq` it reads `after=<its last seq>` once; a
  read already in flight is not doubled, a second one follows if `logSeq`
  moved on meanwhile. It does not poll on a timer while the socket is live;
  without a live socket it falls back to polling (about every 5 s).
- Every advance writes at least the area (its `time`), so every advance
  publishes a batch with `logSeq`.

## Not yet
- A log for changes to a `game` itself, which is in no area.
- Pushing the entries themselves to clients.
