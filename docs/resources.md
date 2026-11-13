# Resources: the game-state contract

Owned by the repo agent. Read this if your module reads, writes or subscribes to
game state (`libs/resource-model`, `services/world`, `services/subscriptions`,
the clients). The rules here are binding across modules; the reasons behind them
are in [`decisions.md`](decisions.md). The library that implements the contract
is [`libs/resource-model`](../libs/resource-model/AGENTS.md); services use it and
never reimplement any of this.

## Shape
Everything in the game context is a **resource**: games, areas, entities and
items.

```json
{
  "id": "area:7f3c…",          // resource id, globally unique, type-prefixed
  "type": "area",
  "version": 41,               // integer, +1 on every change (see below)
  "children": ["entity:…"],    // contained resources, by id
  "state": { … }               // type-specific
}
```

## Glossary
| type | contains | what it is | example |
|---|---|---|---|
| `game` | areas | one run or world | dungeon 123 |
| `area` | entities | one connected space with geometry | citadel 456 |
| `entity` | items | something that occupies a position in an area. Its `kind` is `mobile` (moves by itself), `fixed` (doesn't move) or `player` (a character) | orc 789 |
| `item` | (items, later) | carried or lying around | short sword 012 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`. Use these words in code, APIs
and docs. Don't use "zone", "map", "mob" or "level" for them.

Accounts: a **user** is a login account (the `auth` service). A **character** is
a `player` entity that a user plays. Permadeath kills the character, never the
user.

## Versions
- Every resource has an integer `version`, incremented by one on every change,
  in the same transaction as the write
  (`UPDATE … SET version = version + 1 RETURNING version`; the row lock keeps
  the sequence gapless).
- A resource's version covers its own state **and its list of children**:
  adding or removing a child bumps the parent; a child's state change bumps
  only the child.
- There is **no hash**. Clients never hash or canonicalise anything.
- A client compares a notification's version with its cached one:
  - cached + 1: apply the change;
  - higher: it missed something, so it `GET`s the resource again;
  - equal or lower: ignore it (duplicate).
- Two checks catch a missed *last* update, which no later message would reveal:
  1. On subscribe (so on every reconnect) the server sends a `digest` of the
     current versions of the area and everything in it.
  2. Every ~30 s the server sends each socket the same digest for each of its
     subscribed areas. Any entry higher than the cached version triggers a `GET`.
- REST `GET` returns the resource with its `version` and uses it as the `ETag`
  (`"<version>"`); `If-None-Match` (also `W/`) gets a 304.

## Generic resource API
Three read-only operations, defined in `libs/resource-model` so any service can
serve them. `world` is the only implementation for now, under
`/api/world/resources`. A JWT is required.
1. **List by filter:** `GET {prefix}?type=game&<stateKey>=<value>…`, paged.
   `type` is required; any other query parameter is an equality filter on a
   top-level `state` key.
2. **List children:** `GET {prefix}/{id}/children?type=area`, paged.
3. **Get one:** `GET {prefix}/{id}`.

Pages: `page` (default 0) and `size` (default 50, max 200); the body is
`{"items":[…],"page":0,"size":50,"total":N}`.

## Change notifications over Postgres `LISTEN/NOTIFY`
- A service publishes in the **same transaction** as the write, so Postgres
  delivers the notification only if the transaction commits.
- **One notification per area per tick**, carrying a batch:
  `{"area","changes":[{"id","type","version","patch"}],"traceparent"}`.
  **For now a `patch` is always the full new `state` and replaces it.** Deltas
  are a later optimisation and will be marked as such.
- NOTIFY allows 8KB (the library splits at 7900 UTF-8 bytes). A bigger batch is
  sent as several notifications; a single change that doesn't fit goes without
  its `patch`, and clients `GET` it.
- `traceparent` is the W3C trace context of the writing transaction (absent if
  none). Subscribers continue the trace from this field.
- The `subscriptions` service holds the client WebSockets and `LISTEN`s. Clients
  subscribe to an **area** (the area and everything in it), never to single
  entities. See [`websocket.md`](websocket.md).
- NOTIFY isn't durable, and that's accepted: a lost notification is caught by
  the version checks above.
- Services publish and subscribe **only through the interface in
  `libs/resource-model`**, never by calling `pg_notify` or `LISTEN` themselves.

## Ticks and ownership
- Changes in an area are applied by a **tick loop** (a scheduled loop with a
  sleep). A tick is one transaction: all its changes commit together and are
  published as one batch. Clients never see half a tick.
- **The area is the unit of ownership.** One `world` instance owns an area at a
  time and applies its actions one at a time: deterministic order, no locking
  inside an area. *Not built yet:* spreading areas across instances (a lease
  row in Postgres) and unloading an area when its last subscriber leaves.

## Game time
- Game time is a float measured in **time units**. Every area has its own
  clock, stored in the area's state as an anchor:
  `"clock": {"gameTime": 1042.0, "wallTime": 1794441600000, "rate": 1.0}`
  (`wallTime` in epoch milliseconds, `rate` in time units per wall-clock
  second). The current game time is
  `gameTime + (nowMillis - wallTime) / 1000 * rate`. Clients compute it the
  same way; nobody writes the clock every tick.
- The clock is pinned to the wall clock with `rate = 1.0`, so for now one time
  unit lasts one second. Pausing or changing speed means writing a new anchor.
- An area without a `clock` gets `{gameTime: 0.0, wallTime: now, rate: 1.0}`
  on its first tick.
- **Game time never runs away from the area.** If the earliest due event (see
  below) is more than 5 time units in the past, the area was not being ticked
  (restart, crash, stall). The tick then writes a new anchor that sets the
  current game time to that event's time and carries on from there. Nothing is
  skipped and nothing is replayed in a burst.

## Actions
A resource changes the game state only through **actions**. A resource with no
actions does nothing by itself; it can still be acted upon.

Three keys in a resource's `state`:

```json
{
  "actions": ["orbit-step"],                       // what it can do (action types)
  "action": {                                      // what it is doing now; absent when idle
    "type": "orbit-step",
    "params": {"center": {"x": 5.0, "y": 4.0}, "radius": 3.0, "arc": 0.5},
    "start": 1042.0,                               // game time
    "end": 1043.0,                                 // game time; end - start is the duration
    "effect": "end"                                // when it changes the state: start, end or none
  },
  "behaviour": {                                   // how it picks its next action; absent: it never starts one
    "kind": "repeat",
    "action": {"type": "orbit-step", "params": {"center": {"x": 5.0, "y": 4.0}, "radius": 3.0, "arc": 0.5}}
  }
}
```

- **One action at a time** per resource. An action's type must be listed in the
  resource's `actions`.
- **Duration** is decided by the action type from its params and the
  resource's state when the action starts, and is fixed from then on
  (`start`, `end`).
- **Effect timing** belongs to the action type and is copied to `effect`:
  - `start`: the state changes when the action starts. The action is
    committed; the rest of its duration is recovery.
  - `end`: the state changes when the action ends. Until then it is a
    wind-up: if the action is cancelled (the resource is destroyed, or
    something interrupts it), the effect never happens.
  - `none`: the action only takes time.
- **Events.** An action produces two events, its start and its end. A tick
  processes every event of its area whose time is at or before the current
  game time, in order of time, ties by resource id. When an action ends, the
  next one chosen by `behaviour` starts **at the previous action's `end`**, not
  at the time of the tick, so tick timing never stretches a chain of actions.
  An idle resource with a `behaviour` starts its first action at the current
  game time.
- **One write per resource per tick.** However many events a resource had in a
  tick, it is written once with its final state: version +1, one change in the
  batch. A tick with no due events writes and publishes nothing (except a
  due log snapshot, which touches no resource).
- **Every change is logged.** Each write in an area has an entry in the area
  log, committed with it ([`log.md`](log.md)). A started action carries the
  `seq` of its log entry in `action.seq`.
- Because `action` is in the state, clients know what is in progress and may
  animate it (`start`, `end`, `params`). The server's state remains the truth.
- How players submit actions as intents isn't designed yet; it will fill the
  same `action` slot.

### Behaviours
A behaviour picks the next action each time one is needed (at the end of the
previous action, or when the resource is idle).

| kind | shape | what it picks |
|---|---|---|
| `repeat` | `{"kind":"repeat","action":{"type","params"}}` | the same action every time |
| `random` | `{"kind":"random","choices":[{"weight":1.0,"action":{"type","params"}}, …]}` | one of the choices, with probability `weight / sum of weights` |

- Weights are positive, finite floats; there is at least one choice. Every
  choice's action type must be in the resource's `actions`. A behaviour that
  breaks these rules starts nothing.
- **A random pick is recorded on the action it started**, with everything
  needed to check it:

  ```json
  "draw": {"dist": "categorical", "weights": [1.0, 1.0], "u": 0.6180339887, "value": 1}
  ```

  `u` is uniform in `[0, 1)`; `value` is the index into `choices` of the first
  choice whose cumulative weight divided by the sum is greater than `u`. An
  action that wasn't picked at random has no `draw`.
- Each start in a chain draws again. When a tick runs several chained actions,
  only the last one is in the written state (one write per resource per tick);
  every action and draw is in the area log ([`log.md`](log.md)).
- For now `world` draws from one generator of its own. Seeding, the other
  distributions and the shared format of a draw record belong to the
  randomness library that is still to be built (`AGENTS.md`, "Randomness").

### Action types
| type | effect | duration | what it does |
|---|---|---|---|
| `orbit-step` | `end` | `abs(arc) / state.speed` | Moves the entity along the circle `center`, `radius` by arc length `arc` (positive is counter-clockwise, y up). The new angle is the entity's current angle around `center` plus `arc / radius`; the position is recomputed from `center` and `radius`, so the radius never drifts. |

| `wait` | `none` | `params.duration` (above 0) | Nothing. The resource spends the time. |

`speed` is an entity state key: distance per time unit.

## The test area (live since 2026-11-11)
One game with one area, a 10×8 room with a `fixed` pillar and a `mobile`
entity that orbits it in fits and starts: its `random` behaviour picks, with
equal weights, between `orbit-step` (radius 3.0, arc 0.5, speed 0.5: one time
unit per step) and `wait` (duration 1.0).
Fixed ids: game `game:00000000-0000-4000-8000-000000000001`, area `…0002`,
pillar `entity:…0003`, orbiter `entity:…0004`. Details in
[`services/world/AGENTS.md`](../services/world/AGENTS.md).
