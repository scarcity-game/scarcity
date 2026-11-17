# Actions: how game state changes over time

Owned by the root agent. Read this if your module changes game state or shows
what resources are doing (`services/world`, `services/orbital`, the clients).
The behaviours, effects and action types (the catalogue) are in
[`action-types.md`](action-types.md). The shape of
resources, versions, the read API and notifications are in
[`resources.md`](resources.md); the record of what happened is in
[`log.md`](log.md); reasons are in [`decisions.md`](decisions.md).

## Game time
- Time is a float measured in **time units**. A game has one **game time**
  (the source of truth clients show), and every area has its own **area
  time**, stored in the area's state: `"time": 1042.0`. It is the time of the
  last event the area processed. The two are equal wherever a player is. They
  differ only while an area is being warmed up (made) or caught up (returned
  to); see "Crossing areas". Neither is tied to the wall clock: time passes
  only when an area **advances**, and an area advances only when a controlled
  resource is given an action (below, "Intents").
- **An advance** is one transaction. It starts the intent's action at the
  area's `time`, then processes due events in order (below, "Events") until a
  controlled resource is idle again, which is the moment the game needs the
  next decision. It finishes every event at that same game time and then sets
  `time` to it. If no events are left before that, `time` stays at the last
  event processed. Everything in between (projectiles, other resources'
  behaviours, effects) is resolved in that one transaction, instantly in wall
  time.
- **An area runs only while a player or an npc is in it, or one is
  arriving.** An area with neither (only mobs and fixed things) never
  advances and gives no turns: its time stands still, and the actions in
  progress in it are paused, until someone arrives (#190).
- **One resource acts at a time.** The advance stops when a controlled
  resource is idle; which one may decide next is in the area (below, "Turns").
- A new area starts at `time: 0.0`; in the test area the orbiter is up from
  `0.0`.
- Clients show `time` as the current game time. They may animate what
  happened during the last advance (from the actions in the state and the
  log) at any wall-clock pace they like; the server's state is the truth.

## Crossing areas
Stairs lead to another area; taking them is the **`traverse`** action. The
decision and its reasons are in `decisions.md` (2026-11-16, crossing areas).
- **Levels are made lazily.** Stairs with a null `to` lead to an area not made
  yet. The first `traverse` makes it, deterministically from the game seed and
  the depth. (Until #172 a made level is an empty room with the stairs back.)
- **One transaction** takes the entity out of the old area, puts a
  **placeholder** at the stairs in the new area (it holds the entity's id and
  attributes and takes no part in play), and, on a first visit, creates the
  new area. A made area has no other lock holder, so a first visit locks only
  the old area; a return to an existing area locks both, in area id order (today
  the old area is locked first, by the advance, so two crossings in opposite
  directions at once could deadlock: one player per game rules that out). The
  entity is always somewhere, so a crash cannot lose it.
- **Warm-up.** The new area then advances in a series of advances, its mobs
  acting without the player, from `game time - 500` up to game time. Warm-up
  never adds to game time. When area time reaches game time, the placeholder is
  replaced by the entity (same id, re-parented) and the area is the player's.
- **Leaving.** When the last player or npc leaves, the advance stops at the
  departure: area time stops there, and nobody in it is up.
- **Catch-up.** An area returned to starts at `game time - min(game time since
  the player left, 500)` and runs to game time the same way. The arrival is
  what lets it run: the actions paused when it was left end, and the services
  that control its mobs get their turns.
- **Who drives it.** Warm-up and catch-up advance like any other: mob and npc
  services take their turns over the intents API at full speed, with no
  wall-clock wait (they decide in memory, in milliseconds). Only an npc that
  simulates a player may wait. If such a service is down, the arrival waits
  (a known limit; see the turn timeout above).
- **Who sees what.** Subscriptions publish to the area an entity is in. The
  player stays in the old area until the new one has caught up and sees the
  new level only from arrival. No wait screen is planned.
- **One player per game, for now.** Game time is the game's, so two players
  in areas on different clocks is not settled; the design assumes one.

## Turns
Only one resource is ever the active entity, the one that decides,, and everything happens in a
fixed order:
- **The queue.** The controlled resources that are idle, ordered by `since`
  (the game time each became idle: the end of its last action), then by id
  (string order). The first is the `activeEntity`; the rest are `pending`. Both are in the
  area's state, written by every advance:

  ```json
  "activeEntity": {"entity": "entity:…0004", "controller": {"kind": "service", "principal": "orbital"}, "since": 12.0},
  "pending": [{"entity": "entity:…000a", "controller": {"kind": "user", "sub": "<uuid>"}, "since": 12.0}]
  ```

  No `activeEntity` key (and an empty `pending`) means nobody is active. The area is
  written every advance anyway, so this adds no writes and travels in the
  advance's batch.
- **Only the active entity may act.** An intent for a pending resource is a 409, as for a
  busy one. When it acts, its advance stops at once if anyone is pending
  (they are idle), and the next one is the active entity. So two resources never decide at
  the same moment, even when their actions end at the same time.
- **An empty area has no active entity.** With no player, npc or arrival in
  it there is no `activeEntity` and `pending` is empty, so an intent for
  anyone there is a 409, even for an idle mob. A warm-up or catch-up stops for
  an idle mob like any advance; its service acts, and the advances go on until
  the arrival ends at game time.
- **Simultaneous events** resolve by time, then id, each depth first (below,
  "Order at the same game time"). Together with the queue, nothing in an
  area happens "at once": every outcome has one order.
- A controller learns its entity is the active one from the `turn` frame on the WebSocket
  (`websocket.md`) and picks one of the entity's `actions`.
- The engine recomputes the queue from the entities at the start of every
  advance, so the stored `activeEntity` never decides alone. A controlled resource
  added outside an advance (none is today; a joining player will be) is in
  `activeEntity`/`pending` only from the next advance. So when `world` starts it
  writes the queue into every area whose stored one is not what its entities
  give (a `queue` log entry, `log.md`): no time passes, nothing starts, an
  entity that was not queued is queued from the area's `time`, and a right
  queue is not touched. Whatever adds a controlled entity later must write
  the queue the same way.
- One slow controller holds up its area. A turn timeout is a follow-up for
  when there are several players.

## Actions
A resource changes the game state only through **actions**. A resource with no
actions does nothing by itself; it can still be acted upon.

Keys in a resource's `state`:

```json
{
  "actions": ["step", "wait"],                     // what it can do (action types)
  "actionInProgress": {                            // what it is doing now; absent when idle
    "type": "step",
    "params": {"to": {"x": 7.6, "y": 5.5}},
    "start": 1042.0,                               // game time
    "end": 1043.0,                                 // game time; end - start is the duration
    "timing": "end"                                // when it changes the state: start, end or none
  },
  "behaviour": {                                   // how it picks its next action by itself; absent: it never does
    "kind": "repeat",                              // "repeat" or "random" (`action-types.md`, "Behaviours")
    "actionSpec": {"type": "wait", "params": {"duration": 1.0}}  // repeat: start this same action again each time
  }
  // or, instead of "behaviour": "controller" (below, "Controllers")
}
```

- **One action at a time** per resource. An action's type must be listed in the
  resource's `actions`.
- **Duration** is decided by the action type from its params and the
  resource's state when the action starts, and is fixed from then on
  (`start`, `end`).
- **Effect timing** belongs to the action type and is copied to `timing`:
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
     everything they trigger, run depth first at once (`action-types.md`, "Effects").
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
  `seq` of its log entry in `actionInProgress.seq`.
- Because `actionInProgress` is in the state, clients know what is in progress and may
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
- A `service` controller is a JWT with `kind=service` whose `sub` is the
  principal, which the service gets for its API key from auth (root
  `AGENTS.md`, "Auth"). The test area's orbiter is played by `orbital`; the
  levels' mice by `microbe`, which hears their turns in every area from the
  turn feed (`websocket.md`). A service reports the draws behind its choice
  in the intent's `reported` (`draw`, and `plan` for the choice's own). A
  `user` controller is a player with a user JWT whose `sub` matches.

### Intents
An **intent** is a controller's choice of the next action for a resource it
controls.

`POST /api/world/intents` (a user's or a service's JWT):

```json
{
  "entity": "entity:00000000-0000-4000-8000-000000000004",
  "actionSpec": {"type": "step", "params": {"to": {"x": 7.6, "y": 5.5}}},
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
| 409 | the entity already has an action, is pending (another one is the active entity, "Turns"), or `ifVersion` doesn't match |
| 422 | unknown action type, type not in the entity's `actions`, invalid params, or a duration that isn't positive and finite |

Errors have the `{"error": "…"}` body of the resource API.
