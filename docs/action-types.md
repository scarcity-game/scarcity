# Action types: behaviours, effects and the catalogue

Owned by the root agent. Read this if your module implements or runs the rules
(`libs/world-model`, `libs/world-engine`). Game time, events, controllers and
intents (how state changes and who decides) are in
[`actions.md`](actions.md); the shape of resources is in
[`resources.md`](resources.md); the record of what happened is in
[`log.md`](log.md); reasons are in [`decisions.md`](decisions.md).

## Catalogue

### Behaviours
A behaviour picks the next action each time one is needed (at the end of the
previous action, or when the resource is idle).

| kind | shape | what it picks |
|---|---|---|
| `repeat` | `{"kind":"repeat","actionSpec":{"type","params"}}` | the same action every time: a sentry that waits forever, a torch that flickers. No draw |
| `random` | `{"kind":"random","choices":[{"weight":1.0,"actionSpec":{"type","params"}}, …]}` | one of the choices, with probability `weight / sum of weights` |

These are the only two kinds. Anything smarter (looking at the area, aiming,
choosing where to go) is a controller's job, not a behaviour's
(`actions.md`, "Controllers"). A new kind is one more implementation of the behaviour
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
  the type, before the next event runs (`actions.md`, "Order at the same game time"). An action started by an effect has its own duration; its end is an
  event like any other and may be due in the same advance.
- Every effect is one entry in the area log, with what caused it
  ([`log.md`](log.md)).
- **Acting upon.** An effect interrupts the action in progress of every
  resource it acts upon, as a target or by changing it, except the resource
  whose own action set it off, a resource it creates, and a resource in the
  recovery of a `start` action (it is logged on the effect's entry as
  `actedUpon`, `interrupted: false`) (`actions.md`,
  "Interruption"). An effect that starts an action on a busy resource
  interrupts that one first. So a `damage`, a push or any later effect needs
  no rule of its own for this.
- **Creating** a resource gives it a type-prefixed id drawn from the area's
  random generator (not logged as a draw) and adds it to its parent's
  children. **Destroying** a resource removes it; an action it had in progress
  is cancelled and never ends.
- A chain of effects that doesn't stop is a bug in the rules: an advance that
  processes more than 100,000 events and effects fails and rolls back.

### Effect types
| type | params | draws | what it does |
|---|---|---|---|
| `line-attack` | `source`, `from`, `at`, `spread`, `projectile` | `scatter`: gaussian, mean 0.0, sd `spread` (radians); `radius`, `mass`: the projectile's, each gaussian (`Draws.vary`) with mean the prototype's value, sd 5% of it, truncated to `[Double.MIN_VALUE, 2 × mean)` | Aims from `from` at the point `at` (`{x, y}`; it must differ from `from`), adds the scatter, and follows the line to the first thing it meets: the nearest obstacle (an entity that doesn't `overlap`, other than `source`, whatever its shape: `resources.md`, "Shapes"). An area is enclosed, so every line meets one. `result`: `{"direction", "hit": <id>, "point"}`. Triggers `create` of the projectile at `from`, with a `fly` action to `point`. **Contact:** if the line is zero long (the thrower touches the entity it aims into), no projectile is created, since a zero-length `fly` has no duration. A touched entity takes the projectile's `damage` at once (no `break`: there is no projectile); an entity that isn't `damageable`, such as a wall, is skipped as for any target with no hp. `result.point` is `from`. |
| `create` | `parent`, `entity`, `actionSpec` (optional) | | Creates `entity` (a typed entity, with its `id` minted by the engine). If `actionSpec` is given, starts that action on it. |
| `damage` | `target`, `mean`, `sd`, `min`, `max` | `damage`: gaussian truncated to `[min, max)` | Lowers the target's `hp` by the value, not below 0.0. `result`: `{"hpBefore", "hpAfter"}`. **Death:** a target left at 0.0 is dead, and the effect triggers `destroy` of it, in the same advance, whatever did the damage (a thrown pebble, later a strike); its `destroy` entry follows the `damage` entry in the log. No corpse is left (#449). A target that is gone or not `damageable` is skipped with no draw (`result`: `{"skipped": "no hp"}`). |
| `break` | `target`, `p`, `item`, `debris` | `break`: bernoulli, `p` | A landing projectile becomes an item (`resources.md`, "Items and stashes"). Triggers `destroy` of the target, then `settle` at the target's position of one item: if true the `debris` item (`{"prototype": name}`, a `mass` item such as `rubble`) with the target's `mass`; if false the `item` (`{"prototype": name}`, such as `pebble`), `count` 1 and the target's `mass` for a `count` item. A target that is gone is skipped, with no draw. |
| `settle` | `at` (`{x, y}`), `item` (a typed item without an id) | | Puts `item` to rest at `at`. The stash it joins is the nearest whose position is within twice its current `radius` (docs/resources.md, "Items and stashes") of `at`, ties by id; with none, it makes a new stash at `at` itself (a `created` change on its entry, as `create` would log). Then, if the stash holds an item like it (the same `prototype`, neither `unique`), it triggers `stack` of `item` onto that one; else `create-item` of `item` in the stash. `result`: `{"stash", "newStash": bool}`. |
| `create-item` | `parent` (a stash), `item` | | Creates `item` (its `id` minted by the engine) as a child of `parent`; the entry also has a `changed` change of `parent`, which is written for it (`resources.md`, "Items and stashes"). `result`: `{"item"}`. |
| `stack` | `target` (an item), `item` | | Adds `item`'s `mass`, and its `count` for a `count` item, to `target` (an item like it: the same `prototype`, neither `unique`). `result`: `{"countBefore", "countAfter", "massBefore", "massAfter"}`, the counts `null` for a `mass` item. |
| `destroy` | `target` | | Destroys the target. |
| `depart` | `entity`, `stairs` | | Removes `entity` from the area and reports it as a departure by `stairs` (docs/actions.md, "Crossing areas"). If the stairs' `to` is null, `to` and `toArea` are set here to the ids the paired stairs and the new area are made with (two ids drawn from the area's generator, not logged as draws). `result`: `{"toStairs", "toArea", "made"}`: the stairs and area the entity arrives at, always set, and whether the area is made for this crossing (a first visit). |
| `fill` | `cell` | | Turns a `floor` cell into a solid one (`wall`, or `rock` if no floor touches it) through `CellMesh.close`: every cell the mesh changes (the cell, neighbours that become walls, walls left touching no floor that become `rock`, and the `clearance` and `width` that moved) is a `changed` change of type `cell`. A cell that became a `wall` gets a `wall` entity naming it, in the level's material (the first wall's, by id; `granite` when the level has none); a wall that became `rock` loses its entity (`destroyed`). `result`: `{"cell", "kindBefore", "kindAfter", "changed", "wallsCreated", "wallsDestroyed"}`. A cell that is gone or not `floor` is skipped (`result`: `{"skipped": reason}`). |
| `dig` | `cell` | | The reverse, through `CellMesh.open`: turns a `wall` cell into `floor`, destroys its wall entity, and makes `rock` neighbours that now touch floor walls (with entities). Same `result`; a cell that is not a `wall` is skipped. |
| `appear` | `placeholder` | | Replaces the placeholder by the entity it holds, at the placeholder's position, with the entity's own id. `result`: `{"entity", "at"}`. |
| `spawn-wait` | `den`, `spawn` (the `spawn` params) | `interval`: gaussian, mean and sd of `spawn.interval`, truncated to `[min, max)` | Draws the wait of a den's cycle and starts a `spawn` on `den` with `waited` set to it, interrupting the arming `spawn` that set this off (see `spawn`). `result`: `{"interval"}`. |
| `spawn-make` | `den`, `spawn` (the `spawn` params) | in this order, as each is reached: `pick`: categorical over the `choices`' weights; `radius`: gaussian (`Draws.vary`, mean the prototype's, sd 5%, truncated to `[Double.MIN_VALUE, 2 × mean)`); `spot`: uniform in `[0, 2π)`, once per angle tried, at most 8; `mass`, `speed` (each `Draws.vary`, only if the prototype has it); `level`, `strength`, `dexterity`, `intelligence` (`Attributes.vary`, only if the prototype has them) | Counts the entities in the area whose `spawner` is `den`; at `spawn.cap` or more it makes nothing and draws nothing (`result`: `{"skipped": "at cap"}`). Otherwise it picks a prototype and draws the mob's `radius`, then tries up to 8 angles for a spot on the ring at distance den radius + mob radius + 0.05 m from the den's centre. A spot is free if the mob, moved there from the den's centre, meets no obstacle (the geometry of `step`: `Shape.contact`, so walls and entities that don't `overlap` count, and a spot outside the area or behind a wall is refused). With none free it makes nothing (`{"skipped": "no room"}`; the draws stay in the log). Otherwise it draws the rest and triggers `create` of the mob (from the prototype: its faction and actions, with the varied `radius`, `mass`, `speed` and attributes, no behaviour, `controller` = `spawn.controller`, `spawner` = `den`). `result`: `{"prototype", "position"}`. |

Draw records, alongside `categorical`:
`{"dist":"gaussian","mean":0.0,"sd":0.2,"value":0.031}`, with
`"truncate":{"min":0.5,"max":4.0}` (min inclusive, max exclusive) when
truncated, `{"dist":"uniform","min":0.0,"max":6.283185307179586,"value":1.9}` (in
`[min, max)`), and `{"dist":"bernoulli","p":0.5,"u":0.12,"value":true}` (true when
`u < p`).

### Action types
The catalogue is kept to what the test area needs. A controller can choose
`step`, `wait`, `throw` and `traverse` (and `strike`, below). `fly`, `decay` and `arrive` are started by effects or by the engine (a placeholder's behaviour). Nothing in the test area decays since stashes (2026-11-24); `decay` stays in the catalogue.

| type | timing | duration | what it does |
|---|---|---|---|
| `step` | `channel` | distance from the position to `params.to`, divided by the entity's `speed` | Moves the entity in a straight line toward `params.to` (`{x, y}`), over its duration (`actions.md`, "Actions"): at each event while it runs it moves on by `speed` × the time since, and `action-progress` records where it has got to. It is interrupted by an effect that acts upon it, and by the first contact of its edge, keeping its own `radius` clear, with an entity that doesn't `overlap`, whatever its shape (`actions.md`, "Interruption"); the other mover of a contact is interrupted too. A step that starts touching an obstacle it moves toward is invalid; one that starts overlapping can step out. `result` on each `action-progress` entry: `{"from", "at", "distance"}` for that part; on the `action-end` or `action-interrupted` entry: `{"to", "reached": bool, "stoppedAt"}`. A zero-length step is invalid, and so is one longer than the entity's **reach**, `speed` × 5 s (give or take 1e-9 m for rounding) (`Step.MAX_DURATION`; 5 m at 1 m/s), so a faster entity can step further. A `to` inside or beyond an obstacle is not: the step is interrupted at it. A step by an entity with a `polygon` is invalid. |
| `wait` | `none` | `params.duration` (above 0) | Nothing. The resource spends the time. |
| `throw` | `start` | `params.recovery` | At its start triggers `line-attack` from the entity's position at the point `params.at`, with `params.spread` and `params.projectile` (the projectile's state, flight speed, damage, break chance, and the items it lands as). The duration is the recovery before the entity can act again. `projectile` is `{"state": <an entity template, `{"prototype": name}`>, "speed", "damageRoll": {"mean","sd","min","max"}, "p": <break chance>, "item": <an item template, what it lands as whole>, "debris": <an item template, what it breaks into>}`, e.g. `"item": {"prototype": "pebble"}, "debris": {"prototype": "rubble"}`. Aiming (choosing `at`) is the controller's decision. |
| `fly` | `end` | distance `from`→`to` / `params.speed` | At its end the entity is at `to`. Then triggers `damage` on `params.hit` (if any) and `break` on itself, with the projectile's `p`, `item` and `debris`. |
| `decay` | `end` | `params.duration` | At its end triggers `destroy` of itself. |
| `traverse` | `end` | 1.0 | `params.stairs` is stairs in this area within 1.0 of the entity. At its end triggers `depart` of the entity by them. |
| `dig` | `end` | 1.0 | A debugging and test action (an entity has it only if its prototype lists it; none does yet): `params.cell` must be a `wall` cell of the area. At its end triggers the `dig` effect on it. |
| `fill` | `end` | 1.0 | The same for a `floor` cell and the `fill` effect. |
| `arrive` | `end` | `params.duration` | A placeholder's action: at its end triggers `appear` of itself. |
| `spawn` | `end` | the drawn wait (`params.waited`), or 0.000001 s for the arming action | A den's action: `params` are `choices` (a list of `{weight, prototype}`), `cap` (a whole number >= 1), `controller` (given to each mob it makes; `brawler` in the arena), `interval` (`{mean, sd, min, max}`: the wait, a gaussian truncated to `[min, max)`, `min` > 0) and, only on the second action of a cycle, `waited`. A duration can't draw, so a cycle is two actions: the den's `repeat` behaviour starts the arming one (no `waited`), whose start triggers `spawn-wait`; that draws the `interval` and starts the second (`waited` set), which interrupts the first at the same game time. At the end of the second it triggers `spawn-make`, then the behaviour starts the next cycle. Started by the den's behaviour: `{"kind":"repeat","actionSpec":{"type":"spawn","params":{…}}}`. |

**The `den`** (prototype `den`, #453) is a `fixed` entity with `overlap: true`
(mobs walk over it), not `damageable`, radius 0.5 m, listing `spawn`. A den in
an area is given the behaviour above, with its `choices`, `cap`, `controller`
and `interval`; the mobs it makes carry its id as their `spawner`
(`resources.md`, "Attributes and factions"), which is how it counts the live
ones: a mob destroyed (killed, say) no longer counts, so the den makes
another after its next wait.

Entity keys used by these (`resources.md`, "Physical attributes"):
`speed` (m/s), `radius` (metres: the entity is a circle that lines can hit
unless it has `overlap`), `damageable` and `hp`.

`orbit-step` (moving along a circle) was removed on 2026-11-13. Orbiting is
now the controller's choice of `step` targets.

### Combat (`libs/combat`)
The fighting rules (#451, epic #449) are a lib of their own: `Combat.standard()`
is the catalogue above plus these, and `services/world` runs it
(`decisions.md`, 2026-11-26). A controller can choose `strike`. Both read
the creature keys of `resources.md`, "Attributes and factions".

#### Combat action types
| type | timing | duration | what it does |
|---|---|---|---|
| `strike` | `start` | the entity's `strikeTime`, else 1.0 | An unarmed blow. `params.target` is another entity in the area with a `radius`, within the striker's **reach** edge to edge: the centre distance minus both radii is at most the striker's `reach`, else its `radius`. The striker needs attributes (`resources.md`, "Attributes and factions") and a radius. At its start triggers `melee-attack` of `target` by the entity. The duration is the recovery; dexterity doesn't shorten it yet. |

#### Combat effect types
| type | params | draws | what it does |
|---|---|---|---|
| `melee-attack` | `source`, `target` | `hit`: gaussian, mean the source's `level`, sd 1.0, not truncated | A blow, set off by `strike`. Before the draw, `source` joins the target's `foes` (`resources.md`, "Factions"), hit or miss. It hits when the draw is higher than the target's `level` (0.0 for a target with no attributes). `result`: `{"targetLevel", "hit"}`. A hit triggers `damage` of the target with mean the source's `strength`, sd 0.1 × `strength`, `min` 0.0 and `max` the largest double (1.7976931348623157E308, so no real upper bound): never negative, and the death rule applies. A source or target that is gone is skipped with no draw (`result`: `{"skipped": "gone"}`), and so are a target that isn't `damageable` (`"no hp"`) and a source with no attributes (`"no attributes"`). |
