# Resources: the game-state contract

Owned by the root agent. Read this if your module reads, writes or subscribes to
game state (`libs/resource-model`, `services/world`, `services/subscriptions`,
the clients). The rules here are binding across modules; the reasons behind them
are in [`decisions.md`](decisions.md). The library that implements the contract
is [`libs/resource-model`](../libs/resource-model/AGENTS.md); services use it and
never reimplement any of this.

## Shape
Everything in the game context is a **resource**: games, areas, entities,
items and cells.

A resource is **flat JSON**: the envelope keys and the resource's own keys sit
side by side, and `resourceKind` says which Java class (`libs/world-model`)
reads it. There is no `state` object.

```json
{
  "resourceKind": "entity",    // game | area | entity | item | cell
  "id": "entity:7f3c…",        // globally unique, kind-prefixed
  "version": 41,               // integer, +1 on every change (see below)
  "children": ["item:…"],      // contained resources, by id
  "entityKind": "mob",         // entities only: fixed | mob | npc | player
  "prototype": "mouse",        // entities only: the named template it was made from
  "name": "mouse",             // …and the entity's own keys: position, hp, actions…
  "position": {"x": 3.0, "y": 2.0}
}
```

`Resource` is an abstract class in `libs/world-model`: `Game`, `Area` and
`Entity` extend it, `Entity` is extended by `Fixed` (and `Stairs`), `Mob`,
`Npc` and `Player`. An entity's `prototype` names a JSON file in
`libs/world-model/src/main/resources/prototypes/`; a prototype whose class has
extra fields (`stairs-down` and `stairs-up`, both `Stairs`) reads as that class. A client never decides the class:
the server reads it from `resourceKind`, `entityKind` and `prototype`, and
rejects what doesn't fit. In the database `resources.state` holds this JSON
without `id`, `version`, `children` and `resourceKind`, which have columns.

## Glossary
| type | contains | what it is | example |
|---|---|---|---|
| `game` | areas | one run or world. A generated one has a `seed`; its `materialPatterns` say how its walls look ("Materials", below) | dungeon 123 |
| `area` | entities, cells | one connected space, enclosed by its walls; it has no size of its own ("Walls", below). Its `depth` is its level: 0 is the first, and a larger depth is further down (absent reads as 0, and it is always written) | citadel 456 |
| `entity` | items (only a stash, for now) | something that occupies a position in an area. Its `entityKind` is `fixed` (doesn't move), `player` (a character), `npc` (a scripted character a service plays like a player), `mob` (a creature that moves by itself, hostile or neutral) | orc 789 |
| `item` | (items, later) | a thing that can't act and can be picked up: lying in a stash ("Items and stashes", below), later carried | pebble 012 |
| `cell` | nothing | one convex piece of an area's floor plan: `floor`, `wall` or `rock`, with its neighbours ("Cells", below). A `wall` entity's shape is its cell's polygon | cell 345 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`, and `area:456 ⊃ cell:345`. Use these words in code, APIs
and docs. Don't use "zone", "map", "mob" or "level" for them.

### Physical attributes (SI units)
Every entity but a placeholder (which only holds an arriving entity's place)
and a stash (which holds items, "Items and stashes") is a physical object, with these keys; the server is the source of truth and
clients never make values up. Units are SI: lengths in **metres**, mass in
**grams**, time in **seconds** (positions and area sizes are metres too).

| key | meaning |
|---|---|
| `mass` | grams. Required on a stored physical entity |
| `radius` | metres; the entity is a circle of this radius, and clients show the diameter as 2 × radius. A stored physical entity has exactly one of `radius` and `polygon` |
| `polygon` | the entity is this convex polygon: 3 or more vertices `{x, y}` in **area coordinates** (metres, not relative to `position`), counter-clockwise, no two equal. Its `position` is the polygon's centroid, written by the server, for selection and the inspector only. Only fixed entities have one for now. A wall has none of its own: its shape is its cell's `polygon` ("Walls", below) |
| `overlap` | boolean: others can stand on or in it (stairs, a stash, a pebble in flight), so it is no obstacle to a step or a thrown line. Always written; absent reads as false |
| `damageable` | boolean: damage affects its `hp`. Always written; absent reads as false |
| `hp` | present exactly when `damageable` is true. An entity whose `hp` reaches 0 is destroyed (`action-types.md`, `damage`), so one still in play has `hp` > 0 |
| `speed` | m/s, on things that move. **1 m/s is the usual speed** of an entity that walks (a person); faster or slower is a choice of its prototype. A `step` goes at most speed × 5 s (`action-types.md`) |

### Attributes and factions
A creature (a mob, and later a player or an npc) has four **attributes**,
floats > 0 that the combat rules read (#449). It has all four or none; an
entity with none (a wall, a pebble) has none of the keys. A creature's
`level` is its attribute, unrelated to an area's `depth`.

| key | meaning |
|---|---|
| `level` | how able it is overall: an attack hits when a draw around the attacker's level exceeds the target's |
| `strength` | how hard it hits: the mean of its unarmed damage |
| `dexterity` | how deft it is (no rule reads it yet) |
| `intelligence` | how clever it is (no rule reads it yet) |
| `reach` | metres, how far its `strike` reaches edge to edge (`action-types.md`). Optional: absent means its `radius`. Set by a prototype |
| `strikeTime` | seconds, how long its `strike` takes. Optional: absent means 1.0. Set by a prototype |
| `faction` | text, the side it is on (`vermin`, `goblin`, `beast`); a creature fights those of other factions. Optional |

A prototype gives its creatures their faction, and attributes from which
each creature's are drawn when it is made: each a gaussian with mean the
prototype's value and sd 5% of it, truncated to `[Double.MIN_VALUE,
2 × mean)` (`Draws.vary`), drawn in the order level, strength, dexterity,
intelligence and recorded with the rest of the creature's draws (a dive
level's mouse: `mouse.level` and so on, after the stairs' masses). The
mouse's prototype: level 1.0, strength 0.5, dexterity 4.0, intelligence 1.0,
faction `vermin`. A mouse stored before these keys existed has none of them
and reads as before.

### Shapes
An entity's **shape** is its `radius` (a circle) or its `polygon`. In Java
it is `Entity.shape()`, a `Shape` that answers the geometry the rules need:
when a moving circle first touches it, whether a circle overlaps it, and its
bounding box. Rules ask the shape and never test which shape it is
(`decisions.md`, 2026-11-23). Only an entity with a `radius` moves, for now.

### Stairs
Stairs (`Stairs`, prototypes `stairs-down` and `stairs-up`) lead to another
area (`actions.md`, "Crossing areas"). Two keys say where:

| key | meaning |
|---|---|
| `to` | the id of the paired stairs entity on the other level. Absent (null) while that level isn't made yet, and on the stairs up at depth 0, which lead nowhere |
| `toArea` | the id of the area that holds the paired stairs, so a reader finds the level beyond without a search (#235). Absent exactly when `to` is |

Both ends of a pair are linked when the level is made, each naming the other
and its area. A pair is never relinked.

### Walls
A **wall** is a `fixed` entity with `overlap: false`, made from the `wall`
prototype and a material ("Materials", below), that names its **cell**
("Cells", below). The cell holds the outline and the adjacency; the entity
holds what the wall is (name, material, mass). The wall's shape, in the
rules and on screen, is its cell's `polygon`, and it has no `polygon` of
its own: one source of truth. An area is open floor wherever no wall covers
it.

```json
{
  "resourceKind": "entity", "id": "entity:…", "version": 1, "children": [],
  "entityKind": "fixed", "prototype": "wall", "name": "granite wall",
  "material": "granite", "mass": 8100000.0,
  "cell": "cell:…",
  "position": {"x": 1.0, "y": 0.25},
  "overlap": false, "damageable": false
}
```

| key | meaning |
|---|---|
| `cell` | the id of its cell, a `wall` cell of the same area. Required. Loading a wall without its cell is an error, not a wall with no shape |
| `material` | what it is made of, a name in the materials catalogue; one per area, drawn when the level is made. A client draws a wall (its cell's polygon) with the pattern its game's `materialPatterns` gives that material (`content/sprites`, `patterns`), or `content/sprites`' `materials` when the game has none |
| `mass` | derived: the material's density × its cell's area × 3 m (a wall stands floor to ceiling). The example is 2.7 t/m³ × 1 m² × 3 m |
| `damageable`, `hp` | as for any entity; false for now (digging comes later, #234) |

- **Enclosed.** The walls of an area leave no gap between its open space
  and the outside; generation and seeds guarantee it, the engine doesn't
  check it. Walls don't overlap one another: each is one cell.
- **No size.** An area has no `width` or `height`. A client fits its view,
  and limits panning, to the bounding box of the shapes it has loaded, and
  never stores it.
- **Collision.** A wall is an obstacle because it doesn't `overlap`, like a
  pillar (`actions.md`, "Interruption"; `action-types.md`, `step` and
  `line-attack`). There is nothing about walls in the rules, and no other
  boundary.

### Cells
An area keeps the **cells** it was generated from (`decisions.md`,
2026-11-25): convex polygons that tile its floor plan with no gap and no
overlap. Each is a resource, `resourceKind: "cell"`, id `cell:<uuid>`, a
child of its area (`GET …/{area}/children?type=cell`, paged). Cells give
the area its structure, and controllers plan paths over them
(`libs/pathfinding`). Collision doesn't use them: it is still a circle
against the obstacles' shapes, a wall's being its cell's polygon, and
nothing tests whether a mover is inside the floor.

```json
{
  "resourceKind": "cell", "id": "cell:…", "version": 1, "children": [],
  "kind": "floor",
  "polygon": [{"x": 0.8, "y": 0.8}, {"x": 1.6, "y": 0.8}, {"x": 1.6, "y": 1.6}, {"x": 0.8, "y": 1.6}],
  "position": {"x": 1.2, "y": 1.2},
  "neighbours": [{"cell": "cell:…"}, {"cell": "cell:…", "width": 2.4}, {"cell": "cell:…", "width": 1.6}, {"cell": "cell:…"}],
  "clearance": 0.4
}
```

| key | meaning |
|---|---|
| `kind` | `floor`: open. `wall`: solid and touching floor (it shares an edge or a vertex with a floor cell); exactly one `wall` entity names it. `rock`: solid and touching no floor; no entity names it |
| `polygon` | 3 or more vertices `{x, y}` in area coordinates, counter-clockwise, convex, no two equal. A vertex may lie on the straight line between its neighbours, where a neighbouring cell was split (below) |
| `position` | the polygon's centroid, written by the server |
| `neighbours` | one entry per edge, in order: entry `i` is the edge from vertex `i` to vertex `i + 1` (the last to the first). `cell` is the cell across it, or null where the tiling ends. `width` is present only between two floor cells |
| `width` | metres: twice the greatest distance from a point on the edge to a non-floor cell (or the tiling's end). A disc of diameter `width` can cross the edge somewhere, and no bigger disc can |
| `clearance` | floor cells only: metres from the centroid to the nearest non-floor cell (or the tiling's end) |

- **A tiling with no T-junctions.** Every edge is shared whole with exactly
  one other cell, or with none where the tiling ends. Both cells hold the
  shared vertices as equal positions, compared exactly. Where one long edge
  would meet two cells, it is two edges, with a vertex between them on the
  line.
- **Passable.** A mover of radius `r` may go from floor cell A to its
  neighbour B when `r <= clearance` of A and of B, and `2r <= width` of
  their edge. Planning uses this; every `step` is still checked by the
  rules (`actions.md`, "Interruption").
- **Changes** are resource changes like any other: version +1, logged and
  published in the area's batch. A cell that changes kind is changed, and so
  are the cells nearby whose `clearance` or `width` that changes. A wall
  entity is created or deleted with it. A **split** deletes the cell,
  creates its pieces (new ids), and changes each neighbour that gains a
  vertex on its shared edge.
- **Who sees them.** For now every cell is readable like every other
  resource. `rock` cells show the floor plan behind the walls, which
  one wall per cell (2026-11-24) keeps the walls from showing. When fog of
  war comes, it filters cells as it filters entities.

### Materials
Walls and pillars are made of a **material** from the catalogue in
`libs/world-model/src/main/resources/materials.json` (`Materials`): each has
a `name`, a `density` in **grams per cubic metre** (so mass needs no
conversion), a `weight` and the `patterns` it may be drawn with.

| material | density (kg/m³) | weight | patterns (first: the fallback) |
|---|---|---|---|
| granite | 2700 | 6 | ashlar, cave-rock, setts, rubble, mossy |
| limestone | 2500 | 5 | ashlar, rubble, mossy, carved |
| sandstone | 2300 | 4 | sandstone, ashlar, rubble |
| basalt | 3000 | 3 | basalt, cave-rock, setts |
| slate | 2800 | 3 | setts, cave-rock, rubble |
| marble | 2710 | 2 | ashlar, carved |
| obsidian | 2400 | 1 | cave-rock, carved, basalt |
| brick | 1920 | 3 | brick |
| concrete | 2400 | 1 | ashlar, setts |
| oak | 750 | 1 | timber |
| pine | 500 | 1 | timber |
| iron | 7870 | 0.5 | setts, carved |
| steel | 7850 | 0.25 | setts, ashlar |
| ice | 917 | 0.5 | cave-rock, carved |
| bedrock | 3000 | 0 | bedrock |

**Bedrock** is a marker: the walls along an area's outline are made of it, to tell players the area ends there and
that digging that way is impossible (there is no digging yet). Its `weight` is 0, which means no level draws it
as its wall material and no game draws patterns for it: `level.material` is a categorical over the materials
with a weight above 0, the same weights in the same order as without bedrock (it is last in the file), and
`materialPatterns` has no `bedrock` key. A client always draws it with the `bedrock` pattern.

- **Per area.** A generated level draws its walls' material once
  (`level.material`, a categorical over the catalogue by `weight`, in its
  generation snapshot, `log.md`). The test room is granite.
- **Derived mass.** A wall or pillar weighs its footprint (polygon area, or
  πr²) of its material, 3 m tall. A prototype with a `material`, a shape and
  no `mass` gets its mass this way (the `pillar`: granite, 6 362 kg).
- **`materialPatterns`** on a `game`: an object from each material to the
  pattern every area of that game draws it with, one of the material's
  `patterns`, drawn from the game's `seed` (below): for each material in
  catalogue order, a uniform index over its patterns
  (`game.pattern.<material>`; none for a material with one pattern). Key
  order carries no meaning (the store may reorder it). Absent on a game
  that has none; a client then uses `content/sprites`' `materials`. The
  test game, built by hand, has no seed and each material's first pattern.
  A stored map that no longer fits the catalogue (a material removed, a
  pattern no longer allowed) is drawn again from the seed when the game is
  read, or set to each material's first pattern without one; never an
  error.

### Games
| key | meaning |
|---|---|
| `name` | text |
| `seed` | a generated game only: `{"value", "build"}`. `value` is an integer in [0, 2^53) (exact in JavaScript), the source of the game's own draws (its `materialPatterns`; its levels are drawn as they are made, and logged in their snapshots, `log.md`). `build` is the git sha of the server that made it (`unknown` from a local build). The same value and build make the same game; a seed from another build may not, so a client should warn its players when `build` differs from the running server's, and the world logs a warning for it at start |
| `materialPatterns` | "Materials", above |

```json
{"resourceKind": "game", "id": "game:…", "version": 2, "children": ["area:…"],
 "name": "Dive game",
 "seed": {"value": 6529064058449557, "build": "3f9c2e1"},
 "materialPatterns": {"granite": "setts", "limestone": "ashlar", "oak": "timber", "…": "…"}}
```

Accounts: a **user** is a login account (the `auth` service). A **character** is
a `player` entity that a user plays. Permadeath kills the character, never the
user.

### Items and stashes
An **item** is a resource of its own, `resourceKind: "item"`, id
`item:<uuid>`. It has a `prototype` (a file in `libs/world-model`'s
`prototypes/items/`, a namespace apart from the entities'), a `name`, and
how much of it there is:

```json
{"resourceKind": "item", "id": "item:…", "version": 1, "children": [],
 "prototype": "pebble", "name": "pebble", "measure": "count", "count": 3.0, "mass": 90.0}
{"resourceKind": "item", "id": "item:…", "version": 2, "children": [],
 "prototype": "rubble", "name": "rubble", "measure": "mass", "mass": 60.0}
```

- **`measure`** says how the item is reckoned and whether it combines, and
  comes from its prototype:
  - `mass`: by weight, like rubble, dirt, guts or water. There is no
    `count`; `mass` is all of it. 123.45 g of rubble and 5 g more is one
    rubble of 128.45 g.
  - `count`: by number, like pebbles, arrows or consumables. `count` is a
    whole number of at least 1 (a JSON number), and `mass` is the total of
    them all (pebbles differ, so it isn't `count` × one pebble). 12 arrows
    and 5 more is one item of 17 arrows.
  - `unique`: one of a kind, like a weapon, armour or a trophy. There is no
    `count`; `mass` is its own. It never combines: a short sword and
    another short sword are two items.
- `mass` is grams and always present, above 0.
- **Like items combine.** Two items are alike when they have the same
  `prototype` and neither is `unique`. Combining adds their `mass`, and
  their `count` for a `count` item; the item that was there keeps its id,
  and the other is never made.
- **An item with anything of its own is `unique`.** When items get stats,
  notes or enhancements of their own (none do yet), an item that has any is
  `unique` whatever its prototype's `measure`: an enhanced arrow is split
  off its stack as one `unique` arrow, and never rejoins it.

An item on the floor is always in a **stash**, even when it's the only one;
no item is a child of an area. A stash is an entity: `entityKind: "fixed"`,
`prototype: "stash"`, a `position`, `radius` (the **stash radius**, derived below),
`overlap: true`, not damageable, no actions, and its items as `children`.
Its `mass` is the total `mass` of its items, kept current with its
`radius`; it isn't a physical object itself, so that is all the mass it
has (its items have it, and it adds none).
- A stash holds at most one `count` or `mass` item of each prototype, since
  like items combine, and any number of `unique` ones.
- **The stash radius comes from its contents:** `radius` (m) is the
  radius of a hemispherical pile of the total `mass` of its items at a bulk
  density of 1.6 t/m³ (1 600 000 g/m³: loose rock packed to about 60%),
  `cbrt(3 × mass / (2π × 1 600 000))`, clamped to [0.1, 1.0] (up to about
  3.35 kg is 0.10, 10 kg 0.144, 100 kg 0.310, 1 t 0.668; from about 3.35 t
  it is 1.0). It is recomputed whenever an item is added, removed or
  combined onto, in the same advance, with its `mass`, and logged as a
  changed `mass` and `radius` like any other change (`Stash.radiusFor`). A new stash starts at 0.1. An item
  that comes to rest within **twice the stash's current radius** of its
  position joins it (`settle`).
- **A stash goes away with its last item.** Whatever removes an item from a
  stash (picking it up, later) destroys the stash in the same advance when
  none is left. There is no empty stash.
- Stashes don't move and don't merge with one another. How an item comes to
  rest in one is the `settle` effect (`action-types.md`).
- **Versions.** A stash whose items are added or removed is written, with
  one bump for all of it in the advance, like an area whose children change.
  A stash made in an advance together with its first item is at version 2:
  made, then given the item. Combining onto an item changes only that item.
- An item carries the `area_id` of its stash's area, so loading an area loads
  its entities and their items together. Its changes are in the area's
  batches and log like an entity's (`"type": "item"`), and it counts in the
  area's checksum (above).

## Versions
- Every resource has an integer `version`, incremented by one on every change,
  in the same transaction as the write
  (`UPDATE … SET version = version + 1 RETURNING version`; the row lock keeps
  the sequence gapless).
- A resource's version covers its own state **and its list of children**:
  adding or removing a child bumps the parent; a child's state change bumps
  only the child.
- An entity that crosses to another area (`traverse`, docs/actions.md) keeps
  its id, so it keeps its sequence: its first version in the new area is the
  last version it had in the old one, plus one. A resource's version never goes
  down and never repeats, whatever its parent.
- There is **no hash**. Clients never hash or canonicalise resource JSON. The
  version checksum below is arithmetic on ids and versions, never on state.
- A client compares a change's version with its cached one:
  - cached + 1: apply the delta;
  - higher: it missed something, so it `GET`s the resource again;
  - equal or lower: ignore it (duplicate).
- Two checks catch what the version rule can't see: a whole missed batch, and
  a missed *last* update that no later change would reveal (#186):
  1. **The logSeq chain.** Every batch carries the area's `logSeq` before the
     advance (`fromLogSeq`) and after it (`logSeq`). A client whose last
     `logSeq` for the area isn't the batch's `fromLogSeq` missed a batch, and
     resyncs (below).
  2. **The version checksum.** On subscribe and about every 30 s the server
     sends a `checksum` of the area and everything in it, with the area's
     `logSeq`. The client computes the same sum over what it holds, and
     resyncs on a mismatch.
- **The checksum** of a set of resources is the sum, modulo 2³², of
  `low32(id) × version` over each resource in the set. `low32(id)` is the
  id's last 8 hex digits read as an unsigned 32-bit number (the low bits of
  its UUID). It's sent as 8 lowercase hex digits. Order doesn't matter, so
  neither side sorts. The set is the area, its entities and their items, but
  not the game.
  - It's 32-bit arithmetic on both sides. In TypeScript, use
    `(sum + Math.imul(parseInt(id.slice(-8), 16), version)) >>> 0`, with no
    BigInt. In Java, `int` arithmetic wraps the same way; print it unsigned.
  - Multiplying ties each version to its id: a missed update, creation or
    delete, and two resources with their versions swapped, all change the
    sum except by a 1-in-2³² coincidence.
- **Resync:** `GET {prefix}/{area}/versions` (below), then `GET` every resource
  whose version differs from the cached one, `GET` the ones it lacks, and drop
  the ones the map doesn't list.
- REST `GET` returns the resource with its `version` and uses it as the `ETag`
  (`"<version>"`); `If-None-Match` (also `W/`) gets a 304.

## Generic resource API
Three read-only operations, defined in `libs/resource-model` so any service can
serve them. `world` is the only implementation for now, under
`/api/world/resources`. A JWT is required.
1. **List by filter:** `GET {prefix}?type=game&<stateKey>=<value>…`, paged.
   `type` is required; any other query parameter is an equality filter on a
   top-level key of the resource's own JSON. At most 16 filters per request
   (each `has` counts too); more is a 400.
   `has=<key>` (reserved, repeatable) is a presence filter: it matches resources
   whose own JSON has `<key>` as a top-level key with a non-null value, e.g.
   `GET {prefix}?type=area&game=game:7f3c…&has=activeEntity` for the areas where
   someone is active. It combines with the equality filters, `game` and paging;
   an empty `<key>` is a 400.
   `game=<game id>` (reserved, not a filter) limits the list to one game: the
   game itself, its areas (`type=area`) or their entities (`type=entity`).
   It combines with the filters and paging, e.g.
   `GET {prefix}?type=entity&entityKind=player&game=game:7f3c…`. A `game` that
   is not a well-formed game id is a 400; an unknown game is an empty page.
   A resource's JSON has no parent key, so this is how a client finds where an
   entity is: list the game's entities, then the area whose `children` has it.
2. **List children:** `GET {prefix}/{id}/children?type=area`, paged. This is
   how a client loads an area's entities, after a `GET` of the area.
3. **Get one:** `GET {prefix}/{id}`.
4. **Versions:** `GET {prefix}/{id}/versions` gives the versions of the
   resource and everything under it, with their checksum (see "Versions"):
   `{"id":"area:…","versions":{"<id>":<version>,…},"checksum":"<8 hex>","logSeq":<seq>}`.
   It isn't paged (about 60 bytes per resource). `logSeq` is the area's, read
   in the same transaction: 0 for an area whose log is still empty, and left
   out for a resource in no area (a game).
   A client uses it to resync; `subscriptions` uses it to start an area's
   version map.
   A client that finds many ids differing loads them with the paged
   children listing (2) rather than one `GET` each.

Pages: `page` (default 0) and `size` (default 50, max 200); the body is
`{"items":[…],"page":0,"size":50,"total":N}`.

## Change notifications over Postgres `LISTEN/NOTIFY`
- A service publishes in the **same transaction** as the write, so Postgres
  delivers the notification only if the transaction commits.
- **Channels.** An area's batches go on its own channel, `area_<32 hex>`
  (the hex digits of the UUID in `area:<uuid>`; for any other id, the first
  32 hex digits of its SHA-256), so a subscriber `LISTEN`s only to the areas
  it serves and `UNLISTEN`s when it stops (#378). Turn notices (below) go on
  one channel, `resource_turns`, that every subscriber `LISTEN`s to.
- **One notification per area per advance**, carrying a batch:
  `{"area","changes":[<change>…],"fromLogSeq","logSeq","traceparent"}`.
  Changes are **deltas** (#186): a batch carries what changed, never a whole
  resource. Loading a resource is always a `GET`.
- **`logSeq`** is the highest `seq` of the area's log after this advance
  (`log.md`), and **`fromLogSeq`** is the one before it, so a batch's
  `fromLogSeq` is the previous batch's `logSeq` (the logSeq chain, under
  "Versions"). They also tell a subscriber which log entries it can read; the
  entries themselves are not in the notification. Every advance writes at
  least one entry, so `logSeq` > `fromLogSeq`. An area's first batch has
  `fromLogSeq` 0.
- **A `<change>`** is one of these, always with `resourceKind` and `id`:
  - **Update** of a resource the subscriber holds at `version` − 1:
    `{"resourceKind","id","version","set":{…},"unset":[…],"childrenAdded":[…],"childrenRemoved":[…]}`.
    `set` holds each top-level key whose value changed, with its whole new
    value (a changed `position` is sent whole, never a nested patch); `unset`
    lists keys that are gone; `childrenAdded` and `childrenRemoved` are child
    ids. Each of the four is left out when empty, except that an update
    with nothing in it carries `"set":{}`, so it never reads as a stub.
    A `null` value counts as absent: a key that becomes null is in `unset`.
    `version` is never in `set`. A subscriber that holds `version` − 1 applies it; one that holds
    less `GET`s the resource; one that holds `version` or more ignores it.
  - **Created:** `{"resourceKind","id","version","created":true}`. Its
    parent's update in the same batch lists it in `childrenAdded`. A
    subscriber that wants it `GET`s it. A smoke bomb that makes forty clouds
    is forty `created` changes and forty `GET`s, which is accepted for now.
  - **Deleted:** `{"resourceKind","id","deleted":true}`, with no version. A
    subscriber drops it. Its parent's update in the same batch lists it in
    `childrenRemoved`.
  - **Stub:** `{"resourceKind","id","version"}`, an update too big for one
    notification. The subscriber `GET`s it.
  - A resource created and destroyed within one advance is never stored and never
    notified; it exists only in the area log.
  - An entity that crosses to another area (`traverse`) is `deleted` from the
    old area's batch and `created` in the new area's.
- **Reload:** `{"area","reload":true,"logSeq","traceparent"}`, with no
  changes, tells subscribers to load the area afresh: `GET` the area, then
  page through its children (and their items). It's sent for the area an entity
  arrives in by stairs (a crossing, `actions.md`): on a first crossing every
  resource in it is new, and on a return the traveller's placeholder is
  written outside an advance. The arrival's warm-up publishes no batch of
  its own; the reload's `logSeq` is the head after it. A publisher may also send it in place of any batch
  too big to be worth splitting. A reload resets the chain: the subscriber's
  last `logSeq` becomes the reload's.
- NOTIFY allows 8KB (the library splits at 7900 UTF-8 bytes). A bigger batch is
  sent as several notifications, each with the batch's `fromLogSeq` and
  `logSeq` and with `"part":<n>,"parts":<total>` (1-based; left out when the
  batch fits in one). A subscriber moves its last `logSeq` on after the last
  part.
- `traceparent` is the W3C trace context of the writing transaction (absent if
  none). Subscribers continue the trace from this field.
- **Turn notices.** With a batch that changes who is up, the publisher also
  sends a small notice on `resource_turns`, so a subscriber can learn whose
  turn it is in an area it doesn't listen to (the turn feed, `websocket.md`):
  `{"area","controller":{…},"traceparent"}` when the batch's update of the
  area sets an `activeEntity` (the notice carries its `controller`), or
  `{"area","read":true,"traceparent"}` when who is up can't be told from the
  batch (a reload, or the area's update was sent as a stub): read the area.
  A batch that leaves `activeEntity` alone sends none.
- The `subscriptions` service holds the client WebSockets and `LISTEN`s. Clients
  subscribe to an **area** (the area and everything in it), never to single
  entities. See [`websocket.md`](websocket.md).
- NOTIFY isn't durable, and that's accepted: a lost notification is caught by
  the logSeq chain or the checksum (under "Versions").
- Services publish and subscribe **only through the interface in
  `libs/resource-model`**, never by calling `pg_notify` or `LISTEN` themselves.

## Advances and ownership
- Changes in an area are applied by **advances** (`actions.md`, "Game
  time"). An advance runs when a controller's intent arrives; there is no
  scheduled loop. An advance is one transaction: all its changes commit
  together and are published as one batch. Clients never see half an
  advance. (Older docs and code call this a *tick*.)
- Advances of one area run one at a time: each takes the area log's head row
  `FOR UPDATE` first (`log.md`).
- **The area is the unit of ownership.** One `world` instance owns an area at a
  time and applies its actions one at a time: deterministic order, no locking
  inside an area. *Not built yet:* spreading areas across instances (a lease
  row in Postgres) and unloading an area when its last subscriber leaves.

## Game time, actions and effects
A resource changes only through **actions**, which take game time, and the
**effects** they set off. Each area has its own game time, which passes only
when a controlled resource acts. Contract:
[`actions.md`](actions.md). Every change is recorded in the area log:
[`log.md`](log.md).

## The test area (live since 2026-11-11)
One game with one area: a room 10 m by 8 m inside, enclosed by four granite walls, with a `fixed` pillar and an `npc`
orbiter that circles it and throws pebbles at it. The orbiter has a
`controller` (`{"kind":"service","principal":"orbital"}`) and no behaviour.
The `orbital` service plays it: when subscriptions tells it the orbiter is the active entity
(a `turn` frame, `websocket.md`), it waits about 1 s of wall time, reads the
state, chooses among the orbiter's `actions` `step` (to the next point on a
circle of radius 3.0 around the pillar), `wait` (duration 1.0) or `throw`
(at the pillar's position, spread 0.2, recovery 2.0), and posts that as an
intent. The orbiter's `actions` are `step`, `wait` and `throw`, at speed
0.5 m/s; it has `radius` 0.3 and is not damageable. With orbital stopped, the
area stands still.
The pillar has `radius` 0.5 and `hp` 100.0. A pebble flies at speed 6.0, does
gaussian damage (mean 2.0, sd 0.5, truncated to [0.5, 4.0)), and when it
lands breaks with p 0.5 into `rubble` of its own mass, or else stays a
`pebble`. Either way it comes to rest as an item in a stash (`settle`), and
nothing decays.
Fixed ids: game `game:00000000-0000-4000-8000-000000000001`, area `…0002`,
pillar `entity:…0003`, orbiter `entity:…0004`. Details in
[`libs/world-seed/AGENTS.md`](../libs/world-seed/AGENTS.md).

The orbiter is played by `services/orbital`; the area stands still whenever
orbital is down.
