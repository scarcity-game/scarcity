# Actions: how game state changes over time

Owned by the repo agent. Read this if your module changes game state or shows
what resources are doing (`services/world`, the clients). The shape of
resources, versions, the read API and notifications are in
[`resources.md`](resources.md); the record of what happened is in
[`log.md`](log.md); reasons are in [`decisions.md`](decisions.md).

## Game time
*Transition (#103):* until #112 is deployed, `world` still runs a
wall-clock tick loop with a `clock` anchor in the area's state (the rules of
2026-11-12, `decisions.md`). This section is the target.

- Game time is a float measured in **time units**. Every area has its own
  game time, stored in the area's state: `"time": 1042.0`. It is the game time
  of the last event the area processed. It is not tied to the wall clock:
  game time passes only when the area **advances**, and an area advances
  only when a controlled resource is given an action (below, "Intents").
- **An advance** is one transaction. It starts the intent's action at the
  area's `time`, then processes due events in order (below, "Events") until a
  controlled resource is idle again, which is the moment the game needs the
  next decision. It finishes every event at that same game time and then sets
  `time` to it. If no events are left before that, `time` stays at the last
  event processed. Everything in between (projectiles, other resources'
  behaviours, effects) is resolved in that one transaction, instantly in wall
  time.
- An area with no controlled resource never advances. Nothing moves in it
  until something controls a resource there.
- A new area starts at `time: 0.0`.
- Clients show `time` as the current game time. They may animate what
  happened during the last advance (from the actions in the state and the
  log) at any wall-clock pace they like; the server's state is the truth.

## Actions
A resource changes the game state only through **actions**. A resource with no
actions does nothing by itself; it can still be acted upon.

Keys in a resource's `state`:

```json
{
  "actions": ["step", "wait"],                     // what it can do (action types)
  "action": {                                      // what it is doing now; absent when idle
    "type": "step",
    "params": {"to": {"x": 7.6, "y": 5.5}},
    "start": 1042.0,                               // game time
    "end": 1043.0,                                 // game time; end - start is the duration
    "effect": "end"                                // when it changes the state: start, end or none
  },
  "behaviour": {                                   // how it picks its next action by itself; absent: it never does
    "kind": "repeat",                              // "repeat" or "random" (below, "Behaviours")
    "action": {"type": "wait", "params": {"duration": 1.0}}  // repeat: start this same action again each time
  }
  // or, instead of "behaviour": "controller" (below, "Controllers")
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
- **Events.** An action produces two events, its start and its end. An
  advance processes the events of its area in order of time, up to the stop
  point described in "Game time". When an action ends, the next one chosen by
  `behaviour` starts **at the previous action's `end`**, not at the time it
  was processed, so a chain of actions is never stretched. An idle resource
  with a `behaviour` starts its first action at the area's `time` when the
  advance begins.
- **Order at the same game time.** Ties are common despite floats: chained
  actions with round durations from round start times land on exactly the
  same value (two resources that wait 1.0 from 10.0 both end at 11.0). The
  order is fixed so that an advance is deterministic and replays the same:
  1. Events are ordered by time, then by resource id (string order). The id
     order is arbitrary but stable. Speed, initiative or reaction rules would
     replace it later as a deliberate rule, not by accident.
  2. An event runs completely before the next one starts: its effects, and
     everything they trigger, run depth first at once ("Effects").
  3. An action that ends and its successor (the behaviour's next pick) are
     one event. A start caused by an effect at time *t* has a positive
     duration, so its end is a new event after *t*.
  4. When the advance reaches the stop point, it still runs the remaining
     events at that same time, in this order, and then stops.

  **Example.** At `time` 10.0 the orbiter (controlled, `…0004`) is idle, and
  an NPC rat (`…0009`, `repeat` wait 1.0) is in the middle of a wait that
  ends at 11.0. orbital posts `throw` (recovery 2.0) at the pillar, 2.4 away.

  | game time | event (in order) | what happens |
  |---|---|---|
  | 10.0 | intent: orbiter starts `throw` | `throw` has effect `start`: `line-attack` draws the scatter and hits the pillar; `create` makes a pebble with `fly` (2.4 / speed 6.0, so it ends at 10.4) |
  | 10.4 | pebble `fly` ends | `damage` on the pillar (draw), `break` (draw: true): the pebble is destroyed, rubble is created with `decay` (ends at 30.4) |
  | 11.0 | rat `wait` ends | `repeat` starts the next `wait` at 11.0 (ends at 12.0) |
  | 12.0 | orbiter `throw` ends | the orbiter is idle: this is the stop point |
  | 12.0 | rat `wait` ends | also at 12.0, so it still runs (`…0009` sorts after `…0004`); its next wait ends at 13.0 |

  The advance stops with `time` 12.0. It writes the orbiter, the pillar, the
  rat and the area once each and creates the rubble; the pebble was created
  and destroyed inside the advance, so it is only in the log. The rubble's
  decay (30.4) and the rat's wait (13.0) wait for later intents.
- **One write per resource per advance.** However many events a resource had
  in an advance, it is written once with its final state. That is both one
  row update in the database (version +1) and one change in the batch
  published to subscribers: the two are the same thing, since the batch lists
  exactly the rows the advance wrote. Intermediate states (the step that was
  followed by another step in the same advance) are only in the log. The area itself is written once per advance (its
  `time`, plus its child list when resources were created or destroyed).
- **Every change is logged.** Each write in an area has an entry in the area
  log, committed with it ([`log.md`](log.md)). A started action carries the
  `seq` of its log entry in `action.seq`.
- Because `action` is in the state, clients know what is in progress and may
  animate it (`start`, `end`, `params`). The server's state remains the truth.
- A resource gets its next action from exactly one place: its `controller`
  (an intent) or its `behaviour`, never both. A resource with neither only
  runs actions that effects start on it (a projectile's `fly`).

### Controllers
A **controller** says who decides what a resource does next:

```json
"controller": {"kind": "service", "principal": "orbital"}   // a service plays it
"controller": {"kind": "user", "sub": "<user uuid>"}          // a player plays it
```

- A resource with a `controller` has no `behaviour`. It never starts an
  action by itself: when idle, it waits for an intent, and the area doesn't
  advance until one arrives.
- **Decisions belong to the controller, outcomes to the game.** Choosing an
  action and its params is the controller's job: what to do, where to move,
  where to aim. The game decides what that choice leads to (scatter, hits,
  damage, breakage) and records every draw in the log. So the rules take
  points, not targets: `step` goes to a point, and `throw` aims at a point.
- A `service` controller is authenticated by API key (root `AGENTS.md`,
  "Auth and JWTs"). The test area's orbiter is played by `orbital`. A `user`
  controller is a player with a JWT whose `sub` matches.

### Intents
An **intent** is a controller's choice of the next action for a resource it
controls.

`POST /api/world/intents` (JWT or `X-Api-Key`):

```json
{
  "entity": "entity:00000000-0000-4000-8000-000000000004",
  "action": {"type": "step", "params": {"to": {"x": 7.6, "y": 5.5}}},
  "ifVersion": 812,                                   // optional: the entity version the choice was made on
  "reported": {"draw": {"dist": "categorical", "weights": [2.0, 1.0, 1.0], "u": 0.42, "value": 0}}  // optional
}
```

- The intent runs one advance ("Game time"). The action starts at the area's
  `time`, under the same rules as any other start (type listed in `actions`,
  params valid, duration positive and finite).
- `reported` is free-form JSON that the controller attaches to explain its
  choice (a service's random pick, say). It is logged verbatim on the
  `intent` entry and marked as reported: the server doesn't check it and it
  changes nothing.
- Intents on one area run one at a time (the area log's head lock), so two
  intents for one resource can't both start.

| status | when |
|---|---|
| 200 | the action started and the area advanced: `{"area", "time", "logSeq", "version"}` (`version`: the entity's version after the advance) |
| 400 | malformed body |
| 401 | no valid credentials |
| 403 | the caller doesn't control the entity |
| 404 | unknown entity |
| 409 | the entity already has an action, or `ifVersion` doesn't match |
| 422 | unknown action type, type not in the entity's `actions`, invalid params, or a duration that isn't positive and finite |

Errors have the `{"error": "…"}` body of the resource API.

### Behaviours
A behaviour picks the next action each time one is needed (at the end of the
previous action, or when the resource is idle).

| kind | shape | what it picks |
|---|---|---|
| `repeat` | `{"kind":"repeat","action":{"type","params"}}` | the same action every time: a sentry that waits forever, a torch that flickers. No draw |
| `random` | `{"kind":"random","choices":[{"weight":1.0,"action":{"type","params"}}, …]}` | one of the choices, with probability `weight / sum of weights` |

These are the only two kinds. Anything smarter (looking at the area, aiming,
choosing where to go) is a controller's job, not a behaviour's
("Controllers"). A new kind is one more implementation of the behaviour
interface (`libs/world-model`), not a branch in the engine.

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
- Each start in a chain draws again. When an advance runs several chained
  actions, only the last one is in the written state (one write per resource
  per advance);
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
  that set it off, in the same advance, depth first, in the order listed for
  the type, before the next event runs ("Order at the same game time"). An action started by an effect has its own duration; its end is an
  event like any other and may be due in the same advance.
- Every effect is one entry in the area log, with what caused it
  ([`log.md`](log.md)).
- **Creating** a resource gives it a type-prefixed id drawn from the area's
  random generator (not logged as a draw) and adds it to its parent's
  children. **Destroying** a resource removes it; an action it had in progress
  is cancelled and never ends.
- A chain of effects that doesn't stop is a bug in the rules: an advance that
  processes more than 100,000 events and effects fails and rolls back.

### Effect types
| type | params | draws | what it does |
|---|---|---|---|
| `line-attack` | `source`, `from`, `at`, `spread`, `projectile` | `scatter`: gaussian, mean 0.0, sd `spread` (radians) | Aims from `from` at the point `at` (`{x, y}`; it must differ from `from`), adds the scatter, and follows the line to the first thing it meets: the nearest entity with a `radius` (other than `source`), else the area's boundary. `result`: `{"direction", "hit": <id or null>, "point"}`. Triggers `create` of the projectile at `from`, with a `fly` action to `point`. |
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
The catalogue is kept to what the test area needs. A controller can choose
`step`, `wait` and `throw`. `fly` and `decay` are started only by effects.

| type | effect | duration | what it does |
|---|---|---|---|
| `step` | `end` | distance from the position to `params.to`, divided by `state.speed` | Moves the entity in a straight line toward `params.to` (`{x, y}`, inside the area). The line is cast like `line-attack`'s: it stops at the area's boundary or at the edge of the first entity with a `radius` it would enter, keeping the mover's own `radius` clear. The stop is computed at the end, against the entities as they are then. `result` on the `action-end` entry: `{"to", "reached": bool, "stoppedAt"}`. A zero-length step, or a `to` outside the area, is invalid. |
| `wait` | `none` | `params.duration` (above 0) | Nothing. The resource spends the time. |
| `throw` | `start` | `params.recovery` | At its start triggers `line-attack` from the entity's position at the point `params.at`, with `params.spread` and `params.projectile` (the projectile's state, flight speed, damage, break chance, debris and decay). The duration is the recovery before the entity can act again. `projectile` is `{"state": <the projectile's state, without position>, "speed", "damage": {"mean","sd","min","max"}, "p": <break chance>, "debris": <the debris's state>, "decay": {"duration"}}`. Aiming (choosing `at`) is the controller's decision. |
| `fly` | `end` | distance `from`→`to` / `params.speed` | At its end the entity is at `to`. Then triggers `damage` on `params.hit` (if any) and `break` on itself. |
| `decay` | `end` | `params.duration` | At its end triggers `destroy` of itself. |

Entity state keys used by these: `speed` (distance per time unit), `radius`
(the entity is a circle that lines can hit), `hp`.

`orbit-step` (moving along a circle) was removed on 2026-11-13. Orbiting is
now the controller's choice of `step` targets.
