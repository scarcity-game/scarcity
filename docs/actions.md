# Actions: how game state changes over time

Owned by the repo agent. Read this if your module changes game state or shows
what resources are doing (`services/world`, the clients). The shape of
resources, versions, the read API and notifications are in
[`resources.md`](resources.md); the record of what happened is in
[`log.md`](log.md); reasons are in [`decisions.md`](decisions.md).

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

### Effects
An **effect** is one instant step that changes resources: it can change any
resource in the area, create or destroy one, draw random numbers, start an
action on a resource, and trigger further effects. Effects are what actions
set off when they start or end; an action's change to its own resource stays
on the action itself.

- An effect and everything it triggers happen at the game time of the event
  that set it off, in the same tick, depth first, in the order listed for the
  type. An action started by an effect has its own duration; its end is an
  event like any other and may be due in the same tick.
- Every effect is one entry in the area log, with what caused it
  ([`log.md`](log.md)).
- **Creating** a resource gives it a type-prefixed id drawn from the area's
  random generator (not logged as a draw) and adds it to its parent's
  children. **Destroying** a resource removes it; an action it had in progress
  is cancelled and never ends.
- A chain of effects that doesn't stop is a bug in the rules: a tick that
  processes more than 100,000 events and effects fails and rolls back.

### Effect types
| type | params | draws | what it does |
|---|---|---|---|
| `line-attack` | `source`, `from`, `target`, `spread`, `projectile` | `scatter`: gaussian, mean 0.0, sd `spread` (radians) | Aims from `from` at the target's position, adds the scatter, and follows the line to the first thing it meets: the nearest entity with a `radius` (other than `source`), else the area's boundary. `result`: `{"direction", "hit": <id or null>, "point"}`. Triggers `create` of the projectile at `from`, with a `fly` action to `point`. |
| `create` | `parent`, `state`, `start` (optional action) | | Creates an entity with `state`. If `start` is given, starts that action on it. |
| `damage` | `target`, `mean`, `sd`, `min`, `max` | `damage`: gaussian truncated to `[min, max)` | Lowers the target's `hp` by the value, not below 0.0. `result`: `{"hpBefore", "hpAfter"}`. |
| `break` | `target`, `p`, `debris`, `decay` | `break`: bernoulli, `p` | If true: triggers `destroy` of the target, then `create` of the `debris` state at the target's position with a `decay` action. If false: starts `decay` on the target. |
| `destroy` | `target` | | Destroys the target. |

Draw records, alongside `categorical`:
`{"dist":"gaussian","mean":0.0,"sd":0.2,"value":0.031}`, with
`"truncate":{"min":0.5,"max":4.0}` (min inclusive, max exclusive) when
truncated, and `{"dist":"bernoulli","p":0.5,"u":0.12,"value":true}` (true when
`u < p`).

### Action types
| type | effect | duration | what it does |
|---|---|---|---|
| `orbit-step` | `end` | `abs(arc) / state.speed` | Moves the entity along the circle `center`, `radius` by arc length `arc` (positive is counter-clockwise, y up). The new angle is the entity's current angle around `center` plus `arc / radius`; the position is recomputed from `center` and `radius`, so the radius never drifts. |

| `wait` | `none` | `params.duration` (above 0) | Nothing. The resource spends the time. |
| `throw` | `start` | `params.recovery` | At its start triggers `line-attack` from the entity's position at `params.target`, with `params.spread` and `params.projectile` (the projectile's state, flight speed, damage, break chance, debris and decay). The duration is the recovery before the entity can act again. |
| `fly` | `end` | distance `from`→`to` / `params.speed` | At its end the entity is at `to`. Then triggers `damage` on `params.hit` (if any) and `break` on itself. |
| `decay` | `end` | `params.duration` | At its end triggers `destroy` of itself. |

Entity state keys used by these: `speed` (distance per time unit), `radius`
(the entity is a circle that lines can hit), `hp`.
