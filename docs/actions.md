# Actions: how game state changes over time

Owned by the root agent. Read this if your module changes game state or shows
what resources are doing (`services/world`, `services/orbital`, the clients).
The behaviours, effects and action types (the catalogue) are in
[`action-types.md`](action-types.md). The shape of
resources, versions, the read API and notifications are in
[`resources.md`](resources.md); the record of what happened is in
[`log.md`](log.md); reasons are in [`decisions.md`](decisions.md).

## Game time
- Time is a float measured in **seconds** (SI units, `resources.md`). A game has one **game time**
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
  `time` to it.
- **Mobs' turns are decided inside the advance.** When the resource that's up
  is played by an intelligence service, world asks the service right there
  ("Intelligence services"), starts its answer like an intent, and the advance
  goes on. So an advance runs to the next turn of a player or an npc service.
  Every mob may move, attack or wait many times on the way, and the player's
  view updates once, at their turn. Each decided turn is logged as its
  controller's `intent`, with its `reported`. The advance stops at a mob's
  turn when the service can't answer now, when its answer breaks the start
  rules, or when the advance has run for `advance-budget` (10 s) of wall
  time. That turn is then dispatched from the outbox, and the advance that
  applies it goes on the same way. If no events are left before that, `time` stays at the last
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
decision and its reasons are in `decisions-settled.md` (2026-11-16, crossing areas).
- **Stairs down and stairs up** are two prototypes, `stairs-down` and
  `stairs-up`, so a controller knows which way they lead from the prototype
  (`decisions-settled.md`, 2026-11-18). Stairs up with a null `to` (depth 0) lead
  nowhere: `traverse` rejects them.
- **Stairs name where they lead**: `to` is the paired stairs and `toArea` the
  area that holds them (`resources.md`, "Stairs"); both are null until the
  level beyond is made.
- **Levels are made lazily.** Stairs down with a null `to` lead to an area
  not made yet. The first `traverse` makes it, generated (`libs/world-gen`,
  #311) from the area's random source; its log begins with a snapshot that
  records how it was made and every draw (`log.md`, `snapshot`).
- **One transaction** takes the entity out of the old area, puts a
  **placeholder** at the stairs in the new area (it holds the entity's id and
  attributes and takes no part in play), and, on a first visit, creates the
  new area. A made area has no other lock holder, so a first visit locks only
  the old area; a return to an existing area locks both, in area id order (today
  the old area is locked first, by the advance, so two crossings in opposite
  directions at once could deadlock: one player per game rules that out). The
  entity is always somewhere, so a crash cannot lose it.
- **Warm-up.** The new area then advances, its mobs acting without the
  player, from `game time - 100` up to game time (`Arrive.WARM_UP`; 500 until
  2026-11-26). With every mob's turn
  decided inside it, that is one advance in the crossing's transaction, so the
  player sees the level for the first time at arrival. Warm-up
  never adds to game time. When area time reaches game time, the placeholder is
  replaced by the entity (same id, re-parented, its version continuing: the last
  version it had in the old area plus one) and the area is the player's.
- **Leaving.** When the last player or npc leaves, the advance stops at the
  departure: area time stops there, and nobody in it is up.
- **Catch-up.** An area returned to starts at `game time - min(game time since
  the player left, 100)` and runs to game time the same way. The arrival is
  what lets it run: the actions paused when it was left end, and the services
  that control its mobs get their turns.
- **Who drives it.** Warm-up and catch-up advance like any other: world
  asks the mobs' intelligence services inside the advance ("Intelligence
  services"), and npc services take their turns over the intents API, all
  at full speed with no wall-clock wait (they decide in memory, in
  milliseconds). Only an npc that
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
- A controller learns its entity is the active one in one of two ways, and
  picks one of the entity's `actions`. A player or an npc service gets the
  `turn` frame on the WebSocket (`websocket.md`). An **intelligence
  service** is POSTed the turn by world ("Intelligence services", below).
- The engine recomputes the queue from the entities at the start of every
  advance, so the stored `activeEntity` never decides alone. A controlled resource
  added outside an advance (none is today; a joining player will be) is in
  `activeEntity`/`pending` only from the next advance. So when `world` starts it
  writes the queue into every area whose stored one is not what its entities
  give (a `queue` log entry, `log.md`): no time passes, nothing starts, an
  entity that was not queued is queued from the area's `time`, and a right
  queue is not touched. Whatever adds a controlled entity later must write
  the queue the same way.
- One slow controller holds up its area. A turn timeout for players and npc
  services is a follow-up for when there are several players (#151). An
  intelligence service has no timeout: when it is down, its areas pause
  ("Intelligence services").

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
    "timing": "channel"                            // when it changes the state: start, end, channel or none
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
  (`start`, `end`), except that an action ends early when it is interrupted (below).
- **Effect timing** belongs to the action type and is copied to `timing`:
  - `start`: the state changes when the action starts. The action is
    committed; the rest of its duration is recovery.
  - `end`: the state changes when the action ends. Until then it is a
    wind-up: if the action is cancelled (the resource is destroyed, or
    something interrupts it), the effect never happens.
  - `channel`: the state changes over the whole duration. Before the first
    event at each game time while it runs, the engine moves every channel on
    to that time, in id order, with an `action-progress` entry each
    ([`log.md`](log.md)). So whatever happens then (an attack, another step's
    cast) sees the resource where it has got to, not where it started. The
    channel whose own end is the event about to run is not moved separately:
    its end does the rest. A channel that is interrupted (below) ends there and
    then: its `end` becomes that time. Every
    advance moves its channels on to its last event, so the stored state of a
    resource mid-channel is where it was at the area's `time`. `step` is the
    only channel so far (#188).
  - `none`: the action only takes time.
- **Interruption.** Any action in progress can be interrupted: it ends at
  the current game time, with a cause. Nothing else ends an action early.
  Two things interrupt (`decisions.md`, 2026-11-20):
  1. **An effect acts upon the resource**, as a target or by changing it,
     whatever the effect is and even if it changes nothing. The resource
     whose own action set the effect off, and a resource the effect creates,
     are not interrupted. An effect that starts an action on a busy resource
     interrupts the current action first.
  2. **A contact.** A mover's edge reaches the edge of another entity's
     shape (its `radius` or `polygon`, `resources.md`, "Shapes") that
     doesn't `overlap`. A wall is one of these; there is no other boundary. The
     engine computes the time of the first contact of every channel that is
     moving, with the straight lines and speeds the channels have now, and
     makes it a `contact` event if it falls before the channel's end. Both
     movers of a contact are interrupted, each where it is (a fixed entity
     doesn't move, so it has no action to interrupt). It is
     recomputed after every event, so an entity that appears in a mover's
     way, or a mover that starts toward another, is found at once.

  An interrupted `channel` stays where it got to. An interrupted `end`
  action never applies its effect. A `start` action is committed: while it
  is in its recovery (after its start) it is not interrupted, and an effect
  that acts upon it is only logged (`actedUpon`, below). The entry for an
  interruption is `action-interrupted`
  ([`log.md`](log.md)). An interrupted controlled resource is idle from that
  time, so it joins the turn queue with `since` set to it.
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
  1. Events are ordered by time, then by resource id (string order); a
     `contact` has the smaller id of the two resources. The id
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
  | 10.4 | pebble `fly` ends | `damage` on the pillar (draw), `break` (draw: true): the pebble is destroyed, and 30 g of rubble settles: no stash is within 0.5 m, so a stash is created there holding a new rubble item |
  | 11.0 | rat `wait` ends | `repeat` starts the next `wait` at 11.0 (ends at 12.0) |
  | 12.0 | orbiter `throw` ends | the orbiter is idle: this is the stop point |
  | 12.0 | rat `wait` ends | also at 12.0, so it still runs (`…0009` sorts after `…0004`); its next wait ends at 13.0 |

  The advance stops with `time` 12.0. It writes the orbiter, the pillar, the
  rat and the area once each and creates the stash and its rubble; the
  pebble was created and destroyed inside the advance, so it is only in the
  log. The rat's wait (13.0) waits for later intents.
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
  `AGENTS.md`, "Auth"). The test area's orbiter is played by `orbital` and
  the dive game's diver by `diver`. These are **npc services**: they
  simulate players, so they hear their turns in every area from the turn
  feed (`websocket.md`) and post intents. The levels' mice are played by
  `microbe`, an **intelligence service** ("Intelligence services", below). A service reports the draws behind its choice
  in the intent's `reported` (`draw`, `plan` for the choice's own, and
  `considered` for the draws of the choices it planned and didn't take, or
  found infeasible; `libs/service-controller/AGENTS.md`, "A turn"). A
  `user` controller is a player with a user JWT whose `sub` matches.

### Intelligence services
An **intelligence service** is a service controller that plays mobs
(`microbe` plays the mice, `brawler` the fighters). It holds no socket, keeps no state, and never
calls world. Instead, world POSTs it each turn of an entity it controls,
with the context it needs, and the response carries the chosen intent
(#372, `decisions.md` 2026-11-26).
- **The registry.** world's configuration maps each intelligence principal
  to `{base-url, range}`, e.g. `microbe → http://microbe:8080, 8.0`. A
  service principal that isn't in it is an npc service and gets `turn`
  frames instead. Range is per service for now; a perception stat per
  prototype may replace it.
- **The turn request.** `POST <base-url>/turn` with world's service token
  (`sub=world`, `kind=service`) as `Authorization: Bearer`. The service
  rejects any other caller with `403`. The body:

  ```json
  {
    "area": "area:…", "entity": "entity:…", "version": 812, "since": 12.0,
    "range": 8.0,
    "self": { …the entity, as GET /api/world/resources/{id} returns it… },
    "entities": [ …the other entities in range… ],
    "cells": [ …the cells in range… ],
    "factions": { "goblin": {"hostileTo": ["player"]}, "…": "…" }
  }
  ```

  `factions` is the faction relations of the area's game, its own or the
  standard ones (`resources.md`, "Factions"); a service reads it to know
  whom its entity attacks.

  `version` and `since` are the turn's, as in the `turn` frame. Resources
  are in their full JSON, as the resource API returns them. A cell is in
  range when its polygon comes within `range` of `self`'s position. An
  entity is in range when its position is within `range` of `self`'s, or
  when it is a wall whose cell is in range. Walls come unresolved, naming
  their cell, as in the resource API. Items aren't sent: no intelligence
  plays an entity that holds any.
- **The turn response.** `200` with `{"actionSpec": …, "reported": …}`,
  the intent's body without `entity` and `ifVersion`. world applies it as
  an intent of the principal for the turn's entity, with `ifVersion` set to
  the turn's `version`.
- **In the advance, then the outbox.** A turn is first asked for inside
  the advance that makes it, with the context taken from the area as the
  advance has it ("Game time"). If the advance commits with an
  `activeEntity` whose controller is a registered principal (the service
  couldn't answer, or the budget ran out), the same transaction writes a row
  to world's turn outbox. A dispatcher claims due rows, builds the context
  from the committed area, calls the service outside any area transaction,
  and applies the answer like `POST /intents`, authorisation included. The
  row is deleted once the intent is applied, or when applying it gives
  `409`: the turn is stale, and a newer one exists.
- **No timeout, no default.** A `5xx`, a `4xx`, a connection error, a body
  that isn't an intent, or an intent that applying it rejects with `422` is
  retried, with exponential backoff and jitter (250 ms doubling up to
  30 s), and with no cap on attempts. world never skips the turn or picks
  an action for the service. So when a service is down, the areas waiting
  for it pause, as state would in a real game. The attempts and the last
  error are on the outbox row, and a metric gives the turns waiting per
  principal and the age of the oldest.

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
