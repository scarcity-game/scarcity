# Resources: the game-state contract

Owned by the repo agent. Read this if your module reads, writes or subscribes to
game state (`libs/resource-model`, `services/world`, `services/subscriptions`,
the clients). The rules here are binding across modules; the reasons behind them
are in [`decisions.md`](decisions.md). The library that implements the contract
is [`libs/resource-model`](../libs/resource-model/AGENTS.md); services use it and
never reimplement any of this.

## Shape
Everything in the game context is a **resource**: games, areas, entities and
items.

```json
{
  "id": "area:7f3c…",          // resource id, globally unique, type-prefixed
  "type": "area",
  "version": 41,               // integer, +1 on every change (see below)
  "children": ["entity:…"],    // contained resources, by id
  "state": { … }               // type-specific
}
```

## Glossary
| type | contains | what it is | example |
|---|---|---|---|
| `game` | areas | one run or world | dungeon 123 |
| `area` | entities | one connected space with geometry | citadel 456 |
| `entity` | items | something that occupies a position in an area. Its `kind` is `mobile` (moves by itself), `fixed` (doesn't move) or `player` (a character) | orc 789 |
| `item` | (items, later) | carried or lying around | short sword 012 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`. Use these words in code, APIs
and docs. Don't use "zone", "map", "mob" or "level" for them.

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
- There is **no hash**. Clients never hash or canonicalise anything.
- A client compares a notification's version with its cached one:
  - cached + 1: apply the change;
  - higher: it missed something, so it `GET`s the resource again;
  - equal or lower: ignore it (duplicate).
- Two checks catch a missed *last* update, which no later message would reveal:
  1. On subscribe (so on every reconnect) the server sends a `digest` of the
     current versions of the area and everything in it.
  2. Every ~30 s the server sends each socket the same digest for each of its
     subscribed areas. Any entry higher than the cached version triggers a `GET`.
- REST `GET` returns the resource with its `version` and uses it as the `ETag`
  (`"<version>"`); `If-None-Match` (also `W/`) gets a 304.

## Generic resource API
Three read-only operations, defined in `libs/resource-model` so any service can
serve them. `world` is the only implementation for now, under
`/api/world/resources`. A JWT is required.
1. **List by filter:** `GET {prefix}?type=game&<stateKey>=<value>…`, paged.
   `type` is required; any other query parameter is an equality filter on a
   top-level `state` key.
2. **List children:** `GET {prefix}/{id}/children?type=area`, paged.
3. **Get one:** `GET {prefix}/{id}`.

Pages: `page` (default 0) and `size` (default 50, max 200); the body is
`{"items":[…],"page":0,"size":50,"total":N}`.

## Change notifications over Postgres `LISTEN/NOTIFY`
- A service publishes in the **same transaction** as the write, so Postgres
  delivers the notification only if the transaction commits.
- **One notification per area per tick**, carrying a batch:
  `{"area","changes":[{"id","type","version","patch"}],"traceparent"}`.
  **For now a `patch` is always the full new `state` and replaces it.** Deltas
  are a later optimisation and will be marked as such.
- NOTIFY allows 8KB (the library splits at 7900 UTF-8 bytes). A bigger batch is
  sent as several notifications; a single change that doesn't fit goes without
  its `patch`, and clients `GET` it.
- `traceparent` is the W3C trace context of the writing transaction (absent if
  none). Subscribers continue the trace from this field.
- The `subscriptions` service holds the client WebSockets and `LISTEN`s. Clients
  subscribe to an **area** (the area and everything in it), never to single
  entities. See [`websocket.md`](websocket.md).
- NOTIFY isn't durable, and that's accepted: a lost notification is caught by
  the version checks above.
- Services publish and subscribe **only through the interface in
  `libs/resource-model`**, never by calling `pg_notify` or `LISTEN` themselves.

## Ticks and ownership
- Changes in an area are applied by a **tick loop** (a scheduled loop with a
  sleep). A tick is one transaction: all its changes commit together and are
  published as one batch. Clients never see half a tick.
- **The area is the unit of ownership.** One `world` instance owns an area at a
  time and applies its actions one at a time: deterministic order, no locking
  inside an area. *Not built yet:* spreading areas across instances (a lease
  row in Postgres) and unloading an area when its last subscriber leaves.
- How game time works (action durations, how mobile entities choose actions)
  is undecided.

## The test area (live since 2026-11-11)
One game with one area, a 10×8 room with a `fixed` pillar and a `mobile`
entity orbiting it; `world` writes the new float position every second.
Fixed ids: game `game:00000000-0000-4000-8000-000000000001`, area `…0002`,
pillar `entity:…0003`, orbiter `entity:…0004`. Details in
[`services/world/AGENTS.md`](../services/world/AGENTS.md).
