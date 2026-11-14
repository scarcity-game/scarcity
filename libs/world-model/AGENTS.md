# AGENTS.md: libs/world-model

Typed records for game state and their JSON mapping (package
`com.pessimisticit.scarcity.world.model`), and the rules: the action and
effect types and their catalogue (`…world.model.rules`, #107). Contract: `docs/resources.md`
(resource shapes) and `docs/actions.md` (actions, behaviours, controllers,
draws). Part of #103; the split is in root `AGENTS.md`, under the module table.

**Pure.** Jackson is the only dependency. No Spring, no JDBC, no
`resource-model`: the enforcer rule `pure-model` in `pom.xml` fails the build
if one arrives, directly or transitively. Tests need no Docker. Lint applies
with no baseline entries (`config/lint/README.md`).

## Types
| type | JSON | notes |
|---|---|---|
| `Position(x, y)` | `{"x","y"}` | finite; `distanceTo` |
| `GameState(name)` | game state | |
| `AreaState(name, width, height, time)` | area state | width, height > 0; `time` >= 0 and required (the deployed area's `clock` shape doesn't read; #111 upgrades it from raw JSON); `contains(p)`: x in [0, width), y in [0, height) |
| `EntityState(kind, name, position, speed, radius, hp, actions, action, behaviour, controller)` | entity state | `position` required for every kind; `speed`, `radius` > 0 and `hp` >= 0 when present; at most one of `behaviour` and `controller`; `withPosition`, `withHp`, `withAction` (null: idle) |
| `EntityKind` | `"mobile"`, `"fixed"`, `"player"` | |
| `ActionInProgress(type, params, start, end, effect, seq, draw)` | `state.action` | `end` >= `start`; `seq`, `draw` optional; `params` stays an `ObjectNode` (the action type decodes it, #107) |
| `Timing` | `"start"`, `"end"`, `"none"` | the action's `effect` key |
| `ActionSpec(type, params)` | `{"type","params"}` | an action to start: a behaviour's choice, an intent |
| `Behaviour` (sealed): `Repeat(action)`, `Random(choices)`; `Choice(weight, action)` | `kind`: `repeat`, `random` | only the shape is checked on read; whether it can start is decided when it is used (an invalid behaviour starts nothing, docs/actions.md) |
| `Controller` (sealed): `Service(principal)`, `User(sub)` | `kind`: `service`, `user` | |
| `Draw` (sealed): `Categorical(weights, u, value)`, `Gaussian(mean, sd, truncate, value)`, `Bernoulli(p, u, value)`; `Truncate(min, max)` | `dist`: `categorical`, `gaussian`, `bernoulli` | the log's `"for"` key is not part of the record |
| `Game`, `Area`, `Entity` `(id, version, state)` | not mapped | a resource as the store holds it: id checked against its prefix and a canonical UUID (`Ids`, same rules as `ResourceIds`), version >= 1 |

The JSON tag (`kind`, `dist`) picks the record through Jackson's
`@JsonSubTypes` and nothing else reads it. Code that needs to act on a
behaviour or controller does so through methods on the interface, never
by comparing kind strings: `Behaviour.next(rng)` returns a `Pick(action,
draw)` or throws `InvalidAction` (no choices, a weight that isn't positive
and finite). `Controller` gets its method with its first use (the intent
check, #110).

`Draws` (`gaussian`, `truncatedGaussian`, `bernoulli`, `categorical`,
`pick`) take a `RandomGenerator` and return the `Draw` record; the caller
logs it with what it was for. A truncated gaussian rejects and redraws, and
records only the accepted value.

All records are immutable: lists are copied, and `params` is copied in and
out (`params()` replaces the generated accessor and returns a copy).

**Validation lives in the constructors** (`Check`), not in Bean Validation
annotations, so no record can hold invalid state however it is built: the
pure engine (#109) builds states in Java, and a bad value fails where it is
made. It also keeps the module Jackson-only.

## Rules (`model.rules`)
| type | what |
|---|---|
| `Action<P extends Record>` | `name`, `params()` (the record class), `timing`, `duration(p, self)`, and defaults `validate(p, actor)`, `apply(p, actor) -> Applied(state, result)`, `onStart`/`onEnd(p, actor) -> List<EffectCall<?>>`, `spec(p) -> ActionSpec` |
| `Effect<P extends Record>` | `name`, `params()`, `run(p, EffectContext)`, `call(p) -> EffectCall` |
| `EffectCall<P>(effect, params)` | typed; `type()` and `paramsJson()` for the log |
| `Actor(id, state, area)` | the entity an action runs on and an `AreaView` of its area |
| `AreaView` | `areaId`, `area`, `entities` (sorted by id), `exists`, `peek`; `AreaView.of(…)` is a fixed snapshot |
| `EffectContext extends AreaView` | `time`, `random`, `update(id, UnaryOperator)`, `create`, `destroy`, `startAction(id, ActionSpec)`, `trigger(EffectCall)`, `result(Record)`, `draw(for, draw)` |
| `Catalogue` | immutable, by name. `standard()` is docs/actions.md; tests build smaller ones with `of(actions, effects)` |
| `BoundAction<P>(action, params)` | what `Catalogue.start`/`resume` return; the engine calls it without knowing the type; `inProgress(self, start, seq, draw)` builds `state.action` |

- **`Catalogue.start(spec, actor)` is the one place the start rules
  live** (#73): the type is known and in the entity's `actions`, the params
  decode (strict, as everywhere), `validate` passes, and the duration is
  positive and finite. Each failure is an `InvalidAction` whose message
  names the key: `step params at 'to.y': y: required`, `step: to: … is
  outside the area (10.0 x 8.0)`, `decay: not in the entity's actions […]`.
  Whether the entity is idle is the engine's check (409, not 422).
  `resume(actionInProgress)` decodes an action to end it, with no start rule.
- **Params are records** with their checks in the constructors, like the
  model. A projectile's and a debris's state stay JSON (they have no
  position until created) and are checked as states when the params are
  read (`Templates`), so a bad template can't start the throw.
- **Rule classes are stateless singletons** (`Step.INSTANCE`). An action or
  effect sets off another through the other's instance (`Destroy.INSTANCE.call(…)`,
  `Decay.INSTANCE.spec(…)`), never by name. Nothing in the module switches on
  or compares a type name; the catalogue's map is the only lookup.
- **`RayCast`** is the line cast `line-attack` and `step` share: the nearest
  entry into an entity with a `radius` (lower id on a tie), else the
  boundary; an entity met where the boundary is wins. A mover's own radius
  is kept clear of both (`clearance`). A start inside a circle can leave it;
  a start on its edge (within 1e-9) is blocked going in.
- `step` checks `to` inside the area, a non-zero length and a `speed` at the
  start, and casts at its end against the area then (`apply`); its result is
  `{to, reached, stoppedAt}`. `throw` aims at `at`, which must differ from the
  thrower's position; `line-attack` draws the scatter.

## WorldJson
`toTree`, `toJson`, `fromJson(JsonNode | String, Class)`. One private mapper; the rules:
- **Every key must be known.** An unknown key anywhere (state, position,
  action, behaviour, draw) is an error naming it, never dropped: a key is
  added to the model before anything writes it. Decided on #126 (strict over
  keeping unknown keys in an `extra` map). In a rolling deploy that adds a
  key, the reader ships before the writer.
- **Optional values are absent, never `null`.** An empty `actions` is absent
  too. On input, `null` for an optional key reads as absent.
- **Strict numbers.** No coercion from strings (`"1.0"` fails), no floats
  into ints, null for a required number fails. Non-finite values (a JSON
  number too big for a double reads as Infinity) fail in the constructors.
- `toTree` reads its own JSON text back, so its nodes have the types a parser
  gives (`8812` is an int node). A round trip equals the input as a tree.
- `fromJson(tree, type, what)` puts `what` in the message in place of the
  record's name (`step params at 'to': …`); `toObject` is `toTree` of a record.
- **Errors** are `WorldJsonException`: `"<Record> at '<path>': <key>: <what>"`,
  for example `EntityState at 'behaviour': kind: unknown Behaviour 'zigzag'`
  or `EntityState at 'position': x: must be a finite number, got Infinity`.
  `path()` is the dotted path. Also `<key>: required` and `<key>: unknown key`. Constructor checks throw
  `IllegalArgumentException` with the same `<key>: <what>` message when the
  records are built from Java.

## Tests
`RoundTripTest` (every fixture under `src/test/resources/states/`: today's
seed, the orbiter mid-action with a draw, the controlled orbiter, a player, a
flying pebble, a repeat behaviour; area, game and draws), `InvalidInputTest`
(each error names its key, unknown keys rejected), `ModelTest`
(immutability, withers, ids). The fixtures are copies, not the seed itself:
the seed moves to `libs/world-seed` in #111.

`rules/`: `CatalogueTest` (the catalogue's names equal the tables in
`docs/actions.md`, read from the repo; every start rule), `StepTest`,
`LineAttackTest` (the number checks of world's `PebbleThrowTest`, with
`at`), `EffectsTest`, `DrawsAndBehavioursTest`. Effects run against
`FakeContext`, which applies changes to a map and records the rest; draws
come from `ScriptedRandom`. The event loop is tested in `libs/world-engine`
(#109).
