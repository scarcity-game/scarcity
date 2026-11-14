# AGENTS.md: libs/world-model

Typed records for game state and their JSON mapping. Package
`com.pessimisticit.scarcity.world.model`. Contract: `docs/resources.md`
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
| `GameState(name, extra)` | game state | |
| `AreaState(name, width, height, time, extra)` | area state | width, height > 0; `time` >= 0 and required (the deployed area's `clock` shape doesn't read; #111 upgrades it from raw JSON); `contains(p)`: x in [0, width), y in [0, height) |
| `EntityState(kind, name, position, speed, radius, hp, actions, action, behaviour, controller, extra)` | entity state | `position` required for every kind; `speed`, `radius` > 0 and `hp` >= 0 when present; at most one of `behaviour` and `controller`; `withPosition`, `withHp`, `withAction` (null: idle) |
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
behaviour or controller does so through methods on the interface (#107), never
by comparing kind strings.

All records are immutable: lists and maps are copied, and `params` is
copied in and out (`params()` returns a copy).

## WorldJson
`toTree`, `toJson`, `fromJson(JsonNode | String, Class)`. One private mapper; the rules:
- **Unknown keys survive** on the three state records: they land in `extra`
  (`@JsonAnySetter`/`@JsonAnyGetter`), in input order, and are written back
  unchanged. Everywhere else (position, action, behaviour, draw, ...) an
  unknown key is an error.
- **Optional values are absent, never `null`.** An empty `actions` is absent
  too. On input, `null` for an optional key reads as absent.
- **Strict numbers.** No coercion from strings (`"1.0"` fails), no floats
  into ints, null for a required number fails. Non-finite values (a JSON
  number too big for a double reads as Infinity) fail in the constructors.
- `toTree` reads its own JSON text back, so its nodes have the types a parser
  gives (`8812` is an int node). A round trip equals the input as a tree.
- **Errors** are `WorldJsonException`: `"<Record> at '<path>': <key>: <what>"`,
  for example `EntityState at 'behaviour': kind: unknown Behaviour 'zigzag'`
  or `EntityState at 'position': x: must be a finite number, got Infinity`.
  `path()` is the dotted path. Constructor checks throw
  `IllegalArgumentException` with the same `<key>: <what>` message when the
  records are built from Java.

## Tests
`RoundTripTest` (every fixture under `src/test/resources/states/`: today's
seed, the orbiter mid-action with a draw, the controlled orbiter, a player, a
flying pebble, a repeat behaviour, an entity with content keys; area, game and
draws), `InvalidInputTest` (each error names its key), `ModelTest`
(immutability, withers, ids). The fixtures are copies, not the seed itself:
the seed moves to `libs/world-seed` in #111.
