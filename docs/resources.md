# Resources: the game-state contract

Owned by the root agent. Read this if your module reads, writes or subscribes to
game state (`libs/resource-model`, `services/world`, `services/subscriptions`,
the clients). The rules here are binding across modules; the reasons behind them
are in [`decisions.md`](decisions.md). The library that implements the contract
is [`libs/resource-model`](../libs/resource-model/AGENTS.md); services use it and
never reimplement any of this.

## Shape
Everything in the game context is a **resource**: games, areas, entities and
items.

A resource is **flat JSON**: the envelope keys and the resource's own keys sit
side by side, and `resourceKind` says which Java class (`libs/world-model`)
reads it. There is no `state` object.

```json
{
  "resourceKind": "entity",    // game | area | entity
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
| `game` | areas | one run or world | dungeon 123 |
| `area` | entities | one connected space with geometry. Its `depth` is its level: 0 is the first, and a larger depth is further down (absent reads as 0, and it is always written) | citadel 456 |
| `entity` | items | something that occupies a position in an area. Its `entityKind` is `fixed` (doesn't move), `player` (a character), `npc` (a scripted character a service plays like a player) or `mob` (a creature that moves by itself, hostile or neutral) | orc 789 |
| `item` | (items, later) | carried or lying around | short sword 012 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`. Use these words in code, APIs
and docs. Don't use "zone", "map", "mob" or "level" for them.

### Physical attributes (SI units)
Every entity but a placeholder (which only holds an arriving entity's place)
is a physical object, with these keys; the server is the source of truth and
clients never make values up. Units are SI: lengths in **metres**, mass in
**grams**, time in **seconds** (positions and area sizes are metres too).

| key | meaning |
|---|---|
| `mass` | grams. Required on a stored physical entity |
| `radius` | metres; the entity is a circle of this radius, and clients show the diameter as 2 × radius. Required on a stored physical entity |
| `overlap` | boolean: others can stand on or in it (stairs, rubble, a pebble), so it is no obstacle to a step or a thrown line. Always written; absent reads as false |
| `damageable` | boolean: damage affects its `hp`. Always written; absent reads as false |
| `hp` | present exactly when `damageable` is true |
| `speed` | m/s, on things that move |

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

Pages: `page` (default 0) and `size` (default 50, max 200); the body is
`{"items":[…],"page":0,"size":50,"total":N}`.

## Change notifications over Postgres `LISTEN/NOTIFY`
- A service publishes in the **same transaction** as the write, so Postgres
  delivers the notification only if the transaction commits.
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
One game with one area: a 10×8 room with a `fixed` pillar and an `npc`
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
gaussian damage (mean 2.0, sd 0.5, truncated to [0.5, 4.0)), breaks with
p 0.5 into `rubble`, and pebbles and rubble decay after 20.0.
Fixed ids: game `game:00000000-0000-4000-8000-000000000001`, area `…0002`,
pillar `entity:…0003`, orbiter `entity:…0004`. Details in
[`libs/world-seed/AGENTS.md`](../libs/world-seed/AGENTS.md).

The orbiter is played by `services/orbital`; the area stands still whenever
orbital is down.
