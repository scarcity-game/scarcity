# AGENTS.md: libs/world-model

Typed records for game state and their JSON mapping (package
`com.pessimisticit.scarcity.world.model`), and the rules: the action and
effect types and their catalogue (`…world.model.rules`, #107). Contract: `docs/resources.md`
(resource shapes) and `docs/action-types.md` (actions, behaviours, effects,
draws) and, for controllers, `docs/actions.md`. Part of #103; the split is in root `AGENTS.md`, under the module table.

**Pure.** Jackson is the only dependency. No Spring, no JDBC, no
`resource-model`: the enforcer rule `pure-model` in `pom.xml` fails the build
if one arrives, directly or transitively. Tests need no Docker. Lint applies
with no baseline entries (`config/lint/README.md`).

## Types
| type | JSON | notes |
|---|---|---|
| `Position(x, y)` | `{"x","y"}` | finite; `distanceTo` |
| `Turn(entity, controller, since)` | the `activeEntity` or a `pending` entry | a controlled idle entity and the game time it became idle (docs/actions.md, "Turns") |
| `Resource` (abstract: `id`, `version`, `children`, `resourceKind()`) | one resource as flat JSON | read by `ResourceDeserializer` from `resourceKind`; `withId`, `withVersion`, `withChildren` (null: unknown or unchanged); no `state` |
| `Game(name)` | a game | |
| `Area(name, time, depth, activeEntity, pending)` | an area, with no size: its walls are its edges (#298; a stored `width`/`height` is read and dropped) | `depth` >= 0 (0 is the first level; absent reads as 0, always written); `time` >= 0 and required; `activeEntity` null when nobody is, `pending` never null and empty without one; `withQueue(list)`/`queue()` split and join them |
| `Entity` (abstract) | an entity, by `entityKind`: `Fixed` (`Stairs` adds `to`; `Placeholder` isn't `physical()`), `Mob`, `Npc`, `Player` | `prototype` names its template (`Prototypes`, JSON files in `src/main/resources/prototypes/`; a prototype may name a subclass); `position`, SI units (docs/resources.md): `mass` (g) and `radius` (m) > 0, required when stored if physical, `speed` (m/s) > 0, `overlap`, `damageable`, `hp` >= 0 exactly when damageable, `actions`, `actionInProgress`, `behaviour`, `controller` (at most one of the last two); `radius` or `polygon` (#294; exactly one when stored, never both): `polygon` is a list of `Position` in area coordinates, checked by `ConvexPolygon` (3 or more vertices, no two equal, convex, counter-clockwise, non-zero area; collinear vertices allowed), and `position` is then its centroid, rewritten by `withPolygon`, `withPosition` and `validate`; `material` (optional text; the `stone-wall` prototype is a `fixed` entity with a polygon, built `Prototypes.get("stone-wall").withPolygon(...)`); `shape()` derives a `Shape` from the flat keys; `withPosition`, `withHp`, `withActionInProgress` (null: idle) |
| `Shape` (sealed): `Circle(position, radius)`, `Polygon(vertices)`; `BoundingBox` | `Entity.shape()`, null with neither key; `boundingBox()`, and `contact`/`contactDistance`, the collision geometry (`Sweep`) that `Contacts` and `RayCast` ask of every obstacle without testing which shape it is | not mapped |
| `EntityTemplate(prototype)` | `{"prototype": name}` | an entity not created yet (a projectile's, a debris's, a `create`'s `entity` param); `at(position)` makes the idle entity from the prototype |
| `EntityKind` | `"fixed"`, `"player"`, `"npc"`, `"mob"` | |
| `ActionInProgress(type, params, start, end, effect, seq, draw)` | `actionInProgress` | `end` >= `start`; `seq`, `draw` optional; `params` stays an `ObjectNode` (the action type decodes it, #107) |
| `Timing` | `"start"`, `"end"`, `"none"` | the action's `effect` key |
| `ActionSpec(type, params)` | `{"type","params"}` | an action to start: a behaviour's choice, an intent |
| `Behaviour` (sealed): `Repeat(action)`, `Random(choices)`; `Choice(weight, action)` | `kind`: `repeat`, `random` | only the shape is checked on read; whether it can start is decided when it is used (an invalid behaviour starts nothing, docs/action-types.md) |
| `Controller` (sealed): `Service(principal)`, `User(sub)` | `kind`: `service`, `user` | |
| `Draw` (sealed): `Categorical(weights, u, value)`, `Gaussian(mean, sd, truncate, value)`, `Bernoulli(p, u, value)`; `Truncate(min, max)` | `dist`: `categorical`, `gaussian`, `bernoulli` | the log's `"for"` key is not part of the record |
| `Game`, `Area`, `Entity` `(id, version, state)` | not mapped | a resource as the store holds it: id checked against its prefix and a canonical UUID (`Ids`, same rules as `ResourceIds`), version >= 1 |

The JSON tag (`kind`, `dist`) picks the record through Jackson's
`@JsonSubTypes` and nothing else reads it. Code that needs to act on a
behaviour or controller does so through methods on the interface, never
by comparing kind strings: `Behaviour.next(rng)` returns a `Pick(action,
draw)` or throws `InvalidAction` (no choices, a weight that isn't positive
and finite); `options()` lists every action it may pick, whose types the
engine checks with `Catalogue.listed` before it draws (#109). `Controller` gets its method with its first use (the intent
check, #112).

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
| `Catalogue` | immutable, by name. `standard()` is docs/action-types.md; tests build smaller ones with `of(actions, effects)` |
| `BoundAction<P>(action, params)` | what `Catalogue.start`/`resume` return; the engine calls it without knowing the type; `inProgress(self, start, seq, draw)` builds `state.action` |

- **`Catalogue.start(spec, actor)` is the one place the start rules
  live** (#73): the type is known and in the entity's `actions`, the params
  decode (strict, as everywhere), `validate` passes, and the duration is
  positive and finite. Each failure is an `InvalidAction` whose message
  names the key: `step params at 'to.y': y: required`, `step: to: a
  zero-length step (it is the entity's position)`, `decay: not in the entity's actions […]`.
  Whether the entity is idle is the engine's check (409, not 422).
  `resume(actionInProgress)` decodes an action to end it, with no start rule.
  `listed(spec, state)` is the first two rules alone (known, in `actions`).
- **Params are records** with their checks in the constructors, like the
  model. A projectile's and a debris's state are `EntityTemplate`s, so a bad
  one fails when the params are read and can't start the throw. The only
  JSON left is `ActionSpec.params` and `ActionInProgress.params`: their type
  depends on the action, so the catalogue decodes them (`BoundAction` is the
  typed form; review on #127).
- **Rule classes get their collaborators through constructors.** A rule
  that sets off another takes it as an `Action`/`Effect` (`new Fly(damage,
  breakEffect)`, `new Break(destroy, create, decay)`, `new LineAttack(create,
  damage, fly)`, `new Throw(lineAttack)`, `new Decay(destroy)`, `new
  Traverse(depart)`, `new Arrive(appear)`), so a test can wire a fake.
  `Catalogue.standard()` is the one place that wires them; rules without
  collaborators (`Step`, `Wait`, `Create`, `Destroy`, `Damage`, `Depart`,
  `Appear`) keep an `INSTANCE`. There is no lookup by name; the catalogue's map
  is the only one, and nothing switches on or compares a type name. A
  controller outside the module builds a `throw` or `traverse` spec with
  `Throw.specFor(…)` / `Traverse.specFor(…)` (and `NAME`), which need no
  collaborators (#128).
- **`RayCast`** is the line cast `line-attack` and `step` share: the nearest
  meeting with an obstacle, an entity with a shape that doesn't `overlap`
  (lower id on a tie), walls among them; there is no other boundary, and a
  line that meets nothing fails the advance (the area isn't enclosed). A
  mover's own radius is kept clear of it (`clearance`). A start inside a shape can leave it;
  a start on its edge (within 1e-9) is blocked going in.
- `step` checks a non-zero length and a `speed` at the
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
(immutability, withers, ids), `EntityTemplateTest`. The fixtures are copies, not the seed itself:
the seed moves to `libs/world-seed` in #111.

`rules/`: `CatalogueTest` (the catalogue's names equal the tables in
`docs/action-types.md`, read from the repo; every start rule), `StepTest`,
`LineAttackTest` (the number checks of world's `PebbleThrowTest`, with
`at`), `EffectsTest`, `DrawsAndBehavioursTest`. Effects run against
`FakeContext`, which applies changes to a map and records the rest; draws
come from `ScriptedRandom`. The event loop is tested in `libs/world-engine`
(#109).
