# Decisions and their reasons

Why things are the way they are. The rules themselves live in
[`AGENTS.md`](../AGENTS.md) and the other `docs/` files; read this when you
want to change one. Entries say what holds now: a revised decision is
rewritten in place with the date of the revision, not appended. Superseded
history is in git.

This file holds the **recent and in-progress** decisions: those of the last
few days, and older ones whose work is still open. The rest are in
[`decisions-settled.md`](decisions-settled.md). An entry moves there once
it has stood for a while and its issues are closed; one that is revised
again moves back here with the date of the revision.

## Future ideas the design stays open for
Several players in one area (an advance stops when any controlled resource is
idle), interest management and fog of war, deltas in patches, Redis Streams
for fan-out, area leases across `world` instances, and services that control NPCs at different levels of intelligence through
the same intents API.

## 2026-11-27: an arena is watched by its maker, a player who only waits (#455, #449)
- **What.** `POST /api/world/games` makes a game from a profile; the first,
  `arena`, is a walled room with dens that spawn mobs for `brawler`. The user
  who makes it joins as a `spectator`, a `player` with only `wait`, and the
  game's faction map makes the chosen factions hostile to each other and not
  to `player`.
- **Why.** An area with no player, npc or arrival in it doesn't advance
  (`actions.md`, "Turns"), so an arena of only mobs would stand still. The
  human chose a spectating player over an idle npc or a change to the
  occupancy rule: time in the arena moves when its watcher waits, as it will
  when they play.
- **How.** The profile's options are checked by `ArenaGame.Options`, and
  `GET /api/world/games/profiles` gives a client the mobs and limits. A room,
  not a generated level, so dens are placed on open floor without a search;
  a level is a later option of the profile.

## 2026-11-27: whom a creature attacks is a faction map per game, plus its foes (#454, #449)
- **What.** A game's `factions` maps each faction to the sides it attacks on
  sight (`hostileTo`, factions or `player`); a faction not listed is
  neutral. A game with none plays by `factions.json`. Every creature also
  attacks its `foes`, the entities that attacked it, which `melee-attack`
  records on the target. world sends the game's relations in each
  intelligence turn.
- **Why.** The human: "any other faction" is too blunt. Most factions
  attack only players (goblins), some others too (demons attack angels),
  and the neutral ones attack only after being attacked. An arena needs
  other relations than a dive (its factions fight each other and leave the
  spectator alone), so the map is per game.
- **How.** The relations guide controllers; the rules don't refuse a strike
  at a creature one isn't hostile to, as a player may hit anyone. `foes` is
  on the entity because intelligence services keep no state. It isn't
  pruned yet: a foe that died stays listed and is simply not in range.

## 2026-11-27: sprite size is capped per frame, not per sheet (#465)
- **What.** `content/sprites`' check caps each sprite frame and overlay
  symbol at 2 KB and each pattern tile at 12 KB. It no longer caps a category
  sheet at 12 KB.
- **Why.** The sheet cap had no stated reason and measured the wrong thing:
  repeated slot strings (`var(--sprite-outline, #231d17)`) that gzip removes
  (an 11.5 KB sheet ships as about 1.4 KB). The animated sheets all sat
  within 1 KB of it, so a creature with more limbs or wings went over it by
  its body plan, not by fussy detail. Drawing for the cap
  (`arachnid`) cost more effort than drawing the creature. Readability at
  16 px is checked by eye on the sheet view's silhouette line-up; the byte
  cap only stops pasted-in, tool-exported art. 2 KB is about twice a frame
  today.

## 2026-11-26: the fighting rules are a lib of their own, `libs/combat`, added to the catalogue (#451, #449)
- **What.** `strike` and its `melee-attack` effect live in `libs/combat`,
  pure Java on `libs/world-model`. `Combat.standard()` is `Catalogue.standard()`
  plus them, through `Catalogue.with`, which adds types and refuses a name
  already there. `services/world` runs `Combat.standard()`. world-model
  doesn't depend on combat. It holds only the keys the rules read (the
  attributes, `reach`, `strikeTime`), because an entity's JSON is read
  strictly.
- **Why.** Combat will grow: armour, evasion, weapons, and uses for dexterity
  and intelligence (#449, "Later"). Kept apart, it has its own tests and its
  own reviewers, and world-model stays the shapes and the core rules. The
  catalogue already finds rules by name, so a lib can add some without an
  `if` anywhere (root `AGENTS.md`, "Rules are objects").
- **The first rules.** Reach is measured edge to edge and defaults to the
  striker's radius. The hit is a gaussian around the striker's level, sd 1.0,
  against the target's level. Damage is a gaussian around strength, sd 10%,
  at least 0, through `damage`, so a kill is #450's death rule. The human set
  these. The strike takes a fixed time (`strikeTime`, 1 s) until dexterity
  means something.
- **Rejected.** Putting the rules in world-model: it grows with every combat
  feature. A plug-in found by `ServiceLoader`: the wiring stays explicit in
  one call, as `Catalogue.standard()` is.

## 2026-11-26: stairs name the area they lead to; `depart` always logs it (#235)
- **What.** Stairs carry `toArea`, the area holding their pair, beside `to`;
  both are set when the level beyond is made, or neither (`resources.md`,
  "Stairs"). `depart`'s result always names `toStairs` and `toArea`, with
  `made` for a first visit. Stored linked stairs got `toArea` from their pair's
  area (world `V17`).
- **Why.** A resource has no parent key, so finding the level beyond meant
  listing every area of the game: the web client did it for the stairs panel
  and, up to 15 times, to follow an entity down the stairs, slower with every
  level. Now the panel reads one area by id, a follower reads the `depart`
  entry of the area's log, and `Crossing` no longer looks up the pair's area.

## 2026-11-26: one area per subscriptions socket, hashed on `?area=`; drain with 1012 (#379, #380, #342)
- **What.** A subscriptions socket names its area in the connect URL
  (`?area=area:…`, unencoded) and may subscribe only to that area. Envoy hashes
  on it (`BackendTrafficPolicy`, `ConsistentHash` on `QueryParams`, on its own
  HTTPRoute `scarcity-subscriptions`), so all the sockets of an area land on one
  pod. The turn feed (npc services such as `diver`) uses a socket with no area,
  which goes to a random pod. On shutdown a pod fails readiness, waits 5 s, then
  closes its sockets with `1012` spread over 15 s. Clients reconnect with a
  jittered back-off and resume by comparing the checksum; there is no replay.
- **Why.** Since #378 any number of pods is correct. Routing an area to one
  pod means one version map, one `LISTEN` and one world read per area instead
  of one per pod. The k8s agent checked the cluster: Envoy Gateway 1.9.3 hashes
  on query params with Maglev (about 1/N of keys move per pod change). It never
  moves or closes an upgraded stream when an endpoint leaves, so the pod must
  close its own sockets.
- **Rejected.** A lookup endpoint returning a pod-addressed URL, which needs a
  StatefulSet with per-pod routes; more moving parts for a problem the design
  already tolerates. A redirect frame from any pod to the owner, which needs
  per-pod addressing too. Hashing the turn feed on its principal: one socket
  per npc service gains nothing from routing.
- **Not now.** Hashing world's intents on the area (#445): world keeps no area
  in memory, most callers reach it in-cluster past the Gateway, and the area is
  in the body.

## 2026-11-26: no startup queue sweep (#342, discussion #429)
`world` no longer walks every area at startup (`QueueSweep`, #158, #373). It was a backfill for
areas stored without a queue or whose turn was never dispatched; the turn outbox is now a durable
table written in every advance's commit and the old areas were repaired. With 853 areas it took
~3 minutes per pod, close to the startup probe, made the two-replica rollout exceed the deploy
job's timeout and contended for area locks with live play. The seed already writes its areas'
queues (`TestArea.area`, `DiveGame.area`); the startup runner calls `Seeder.seedAreas()` and
refreshes the queue of only the areas it created, which offers their first turn. Level
generation on a crossing commits through `warmUp`, which writes the queue and offers the turn, so
nothing else relied on the sweep.

## 2026-11-20: an action is interrupted when it is acted upon or its mover touches something (#133, #57, #237)
Three stories: two movers whose paths cross are both stopped when their edges
touch; an entity that is acted upon while moving is stopped where the action
happens; an entity that appears in a mover's way stops the mover when it
reaches the edge. Before this, `step` was checked against obstacles only at
event times (#188): a mover stopped at the right place but at the time of the
next event, and two movers crossing between events never met.
- **One primitive: interrupt.** The engine ends an action in progress at the
  current game time, with a cause. A channel ends where it has got to (it is
  moved on to that time first, as before). An `end` action never applies its
  effect (as `actions.md` already says of a cancelled one). A `start` action
  is committed and its recovery is not interrupted: being acted upon during
  recovery is logged on the effect's entry (`actedUpon`, with `interrupted:
  false`) and changes nothing else. The same goes for any other action once
  it has applied its effect and only has recovery left. Destroying a resource keeps its own
  `action-cancelled` entry, since the resource is gone.
- **Every effect interrupts.** An effect that acts upon a resource, naming it
  as a target or changing it, interrupts that resource's action in progress (unless it is in recovery,
  above), whatever the effect does and even when it changes nothing (a `damage` that
  rolls 0.0). Not interrupted: the resource whose own action set the effect
  off (a thrower isn't stopped by its own `line-attack`), and a resource the
  effect creates. An effect that starts an action on a busy resource
  interrupts the current one first, then starts the new one. That settles
  #133's two cases, and with it #57. Notifications to the player ("you hear a
  distant trumpet") and choosing to ignore an interruption are not part of
  this; they come later and will need their own rule.
- **Contacts are events.** Movers go in straight lines at constant speed, and
  that only changes at an event, so the time at which a moving circle first
  touches another circle (moving or not) or the area's boundary is computed
  exactly: a quadratic in the relative position and velocity, with the sum of
  the radii as the distance. After every event the engine recomputes the
  earliest contact of each channel that is moving, and the earliest one
  before the channel's own end becomes a `contact` event at that time. It
  stops the movers involved, each with an `action-interrupted` entry caused by
  the `contact` entry. So nothing is cast "part by part" at other events any
  more; the cast is for the first contact only.
- **Who blocks.** Every entity with a `radius` that doesn't `overlap` blocks;
  `overlap` entities (stairs, rubble, a pebble) never do. **Both movers stop**,
  each at its own position at the time of contact, edges touching.
- **A step into contact doesn't start.** A `step` whose first instant is
  already touching an obstacle it moves toward is invalid, so a controller
  can't loop on zero-length steps. A step already running when an obstacle
  arrives at its edge is stopped there.
- **Order.** A `contact` event is ordered by its time, then by the smaller id
  of the pair, like the other events. Several contacts at one time run one
  after another, and each recomputes the forecast, so an entity stopped by
  the first is a stationary obstacle for the next. An interrupted controlled
  entity is idle from the interruption (`since`), so it takes its place in the
  turn queue by that time.
- **Cost and exactness.** Contact times are floats. Entities touching are
  separated by at most a small tolerance (`Contacts.TOUCH`, 1e-9 of distance),
  so rounding never lets a stopped mover count as overlapping. Edges within
  it, or overlapping, are touching: a mover closing on them has its contact
  at once, one moving apart has none. A contact within it of the channel's end
  is not early: the channel reaches its end. A mover that `overlap`s touches
  only the boundary. The forecast is
  pairwise; an index of the area's moving entities is needed once an area has
  many (#59).
- Not decided: pushing and knockback (a push is an effect, so it interrupts;
  what it does to the position is for #238), a mover that can ignore
  interruptions, and `fly` as a channel.

## 2026-11-23: an entity's shape is a circle or a convex polygon, walls are fixed entities, and areas have no size (#234)
- **Walls only.** An area is open floor, enclosed by walls. Space is open
  unless a wall covers it; there are no floor entities. Collision is a
  circle against the shapes of the obstacles, and nothing tests "inside".
  - **Alternative considered:** solid by default, with floor polygons
    generation carves out. It makes digging safe without care (a sliver
    between misaligned old and new cells is rock), and the floor cells are a
    ready navigation mesh. But it doubles the entities, and every movement
    must also test that the mover stays inside the union of the floor. Walls
    only keeps one rule (don't touch an obstacle), and digging seals itself
    because walls may overlap (below).
- **Every physical entity has a shape**: a `radius` (a circle) or a
  `polygon` (convex), exactly one. In Java it is `Entity.shape()`, a sealed
  `Shape` (`Circle`, `Polygon`), and each shape answers the geometry the
  rules ask: when a moving circle first touches it, whether a circle
  overlaps it, and its bounding box. `Contacts` and `RayCast` ask every
  obstacle's shape and never test what kind of thing or shape it is, so a
  door or a boulder with a polygon needs no new rule.
  - **A wall is a `fixed` entity** from a prototype (`stone-wall`) with a
    `polygon`. What makes it an obstacle is `overlap: false`, the same as a
    pillar; there is nothing about walls in the rules.
  - **The JSON stays flat**: `radius` or `polygon` is a key of its own, and
    `shape()` is derived from them, so no existing entity changes on the
    wire.
  - **Only circles move**, for now: a `step` by an entity with a polygon is
    invalid. A moving polygon can be decided when the first door needs one.
  - **Alternative considered:** a new `entityKind` `wall` with a class of
    its own and no `mass` (this entry's first version, 2026-11-21), so code
    that reads `radius` never meets a polygon by accident. Asking the shape
    removes that risk, and a kind of its own put walls in every rule that
    finds obstacles.
  - **Not yet: a flag for what blocks lines.** `overlap: false` blocks both
    steps and lines. A grate (stops movers, not arrows) or smoke (stops
    sight, not movers) will need them apart; the flag comes with the first
    one.
- **Vertices are absolute** area coordinates, so neighbouring walls made from
  one tessellation share vertices exactly in floats. `position` is the
  polygon's centroid, written by the server, for selection and the inspector
  only.
- **Walls may overlap one another; they must leave no gap.** An area is
  enclosed: generation and seeds guarantee it, the engine doesn't check. A
  dig's new walls keep their overlap with old walls rather than being
  clipped, which is what seals them.
- **No `width` or `height`.** The walls bound the area. A client fits and
  limits panning to the bounding box of what it has loaded. The area's
  rectangle leaves `Contacts`, `RayCast`, `LineAttack` and `step`'s checks:
  every contact is with an entity now, and every line ends at one.
  Existing areas are migrated to four walls along their old edges, outside
  the old rectangle, so the open space stays the same.
- **The tessellation isn't stored** (#234, 2026-11-20). Its name,
  parameters and draws are in the log of the area it made.
- **Resource generation is random when possible** (2026-11-24). A resource
  made from a prototype (a thrown pebble, a level's mouse) gets its physical
  values (size, mass, speed) drawn around the prototype's, not copied, for
  variety, and every value is a logged draw like any other. The default is
  `Draws.vary`: a normal with sd 5% of the value, truncated to stay positive
  (and below twice the value, so the logged record is finite JSON). The
  hand-made test room's mice stay at the prototype's values so seeds and
  goldens are stable.


## 2026-11-24: a generated wall is one cell, never more (#234)
A tessellation's walls are one `stone-wall` per solid cell that touches an
open cell, each the cell's own polygon. Rock that touches no open cell has
no wall.
- **Why:** a wall the player sees gives away its shape. A long merged wall
  says the rock goes on that far, and so where the area doesn't; cell-sized
  walls say nothing beyond the cell. This matters more with fog of war.
- **Cost:** about six times the walls (80 to 217 on a 24 × 18 m level, up to
  71 KB of JSON, made in one batch that subscribers reload). Pathfinding
  drops the waypoints where walls meet along a straight run, which kept
  planning under 0.1 s.
- **Alternative considered:** merging solid cells into maximal convex
  pieces (#310's rectangles). Fewer entities, but it leaks the layout.

## 2026-11-24: a step goes at most speed × 5 s, and 1 m/s is the usual speed
- A `step` longer than the mover's **reach**, `speed` × `Step.MAX_DURATION`
  (5 s), is invalid. A fast entity picks points further away in one move;
  a slow one has to stop and choose again sooner, which gives the others
  their turns. 5 s keeps a step at room scale for a walker, and a step is
  still interrupted on contact.
- **1 m/s** is the usual speed of an entity that walks: the diver and the
  orbiter are seeded at it (from 0.5). The mouse stays at 0.5 m/s. A
  pebble flies; it doesn't step.
- Entities already stored keep their speed; only new seeds and new dive
  games get the new one.


## 2026-11-24: items are resources, and an item on the floor is always in a stash (#81)
- **Items are resources** (`item:<uuid>`), children of the entity that holds
  them: a stash, for now; later a character's inventory. Each has its own
  version, as the contract has said since 2026-11-09 (`game ⊃ area ⊃ entity
  ⊃ item`).
  - **Alternative considered:** a stash's items inline on the stash entity,
    as plain values. Far smaller, but an item that is carried, named or
    enchanted needs an identity, and moving them out later is a migration.
- **Always in a stash.** A lone item is a stash of one; a stash is destroyed
  with its last item. One rule for everything on the floor, and no item is a
  child of an area. Stash radius 0.5 m (it was 0.3 in the first proposal).
- **Like items combine**, so debris stays bounded without decay. Each item
  prototype has a `measure`: `count` (pebbles: a whole `count`, and the total
  `mass`) or `mass` (rubble: only a `mass`). Alike means the same prototype.
- **A landing pebble breaks** with its `p` into rubble of its own mass, or
  stays a pebble; either way it settles as an item. Nothing in the test area
  decays any more. Rubble is no longer an entity.
- **Settling** is decided once, when an item comes to rest: join the nearest
  stash within the radius, else start one. Stashes don't move or merge.
- **Start clean** (as on 2026-11-14 and 2026-11-23): the deployed test area
  holds pebble and rubble entities, some decaying with params that no longer
  parse, so a migration empties it and the seed runs again.

## 2026-11-24: physical values are derived; walls have a per-level material; a game maps materials to patterns from its seed
- **A materials catalogue** in `libs/world-model` (`materials.json`, read by
  `Materials`): 14 wall materials, each a `name`, a real `density` (grams per
  cubic metre, so a mass needs no conversion: granite 2 700 000, oak 750 000,
  steel 7 850 000), a `weight` for the level draw, and the wall `patterns` a
  game may draw it with. Data, not code: nothing compares a material's name.
  - **Alternative considered:** depth decides the material. Left to #312
    with the other choices by depth; the weights favour stone for now.
- **Walls are made of a material.** The prototype is `wall` (it was
  `stone-wall`, whose `material: "stone"` and 5000 kg no longer describe
  anything): it has no material and no mass, and `Material.wall(polygon)`
  makes one, named "granite wall", weighing density × area × 3 m. Every
  level draws its material once (`level.material`, a categorical by weight,
  logged in its generation snapshot); the hand-built test room is granite.
  Renaming the prototype is safe on the wire: no client reads `stone-wall`
  (the web client draws a polygon by its `material`), and the stored walls
  go with the clean start below.
- **Derived, not written.** A prototype with a `material`, a shape and no
  `mass` weighs that column of it, 3 m tall: the pillar is granite, πr² × 3 m
  (6 362 kg; the 6 360 kg it had was this, rounded). The stairs' masses are
  varied (`Draws.vary`, `stairs.up.mass`, `stairs.down.mass`) around their
  prototypes' 1000 kg, like the mouse's.
  - **Left as data:** the stairs' 1000 kg (a flight of steps has no volume
    we know without making up a fill fraction), creatures' masses (the
    mouse's 20 g, the diver's and orbiter's 70 kg: biology, not geometry),
    the pebble's 30 g (varied when thrown), items (made from what landed,
    so their mass is already derived), and geometry (radii, wall thickness,
    room and level sizes, clearances).
- **A game draws a material → pattern map** (`materialPatterns` on the game)
  when it is made: one of each material's candidate patterns, a uniform
  index per material (`game.pattern.<material>`; none for a material with
  one candidate), so every area of a game made of granite looks alike and
  two games may differ.
- **A generated game has a seed** (`seed: {value, build}` on the game,
  review on #338): the map is drawn from `value` alone, so the game can be
  replayed, and `build` is the git sha of the server that made it, so a seed
  from an older build can be told and its players warned that remaking it
  may differ (the world logs a warning at start for such a game). The draws
  themselves go to the debug log only, not to an area's snapshot: the seed
  replays them. The sha comes from the `GIT_SHA` build arg CI already passes
  to every image, set as `WORLD_BUILD_SHA` in services/world's image
  (`world.build.sha`; `unknown` in a local build).
  - **Alternatives considered:** the git-commit-id Maven plugin or Spring
    Boot's build-info (the Docker context has no `.git`, so they'd need it
    passed in anyway), and logging the draws in depth 0's snapshot (first
    proposal; a game's draws don't belong to one of its areas).
- **A stored map that no longer fits is remade, not an error**: a material
  gone from the catalogue, or a pattern it no longer allows, makes `Game`
  draw the map again from its seed (each material's first pattern without
  one) when it is read, and it is written so the next time the game is.
- The test game is built by
  hand and draws nothing: its map is each material's first pattern, which
  is also `content/sprites`' fallback `materials`. The world model can't
  read `content/sprites`, so the candidates live in the catalogue, and the
  sprites test checks that each names a pattern that exists.
- **Start clean** (`V14__start_clean`), as on 2026-11-23: the stored walls
  are `stone-wall`s and the games have no map or seed.

## 2026-11-25: an area keeps its cells; walls name their cell; paths are planned over floor cells (#349, #350)
An area stores the cells it was generated from as `cell` resources:
`floor`, `wall` or `rock`, each with its neighbours, and with `clearance`
and `width` for planning (`resources.md`, "Cells"). A `wall` entity names
its cell and takes its shape from it. This reverses part of 2026-11-23,
which didn't store the tessellation and turned down floor cells as a
navigation mesh.
- **Why:** walls will change. Digging, doors and the Abyss (walls that
  appear and vanish) change a cell's kind. Cave-ins and explosions split
  cells. With the cells stored, each of these is a change to a few cells
  that paths, clients and the log already follow. Without them, every
  change would have to be fitted into walls that don't know their
  neighbours.
- **Why not plan from the walls' shapes:** the visibility graph over wall
  corners (#237) grows as corners² × entities, rebuilt every turn. It ran
  the diver out of memory on a 205-wall level (#346), and caching it means
  invalidating it on every change above.
- **Kept from 2026-11-23:** collision is one rule, a circle against the
  obstacles' shapes. Cells are for structure and planning, and no rule
  tests whether a mover is inside the floor. The objection then, that every
  movement must test it stays in the floor, doesn't apply.
- **Kept from 2026-11-24:** one wall per cell, never bigger.
- **One source of truth.** The cell holds the outline and the adjacency;
  the wall entity holds what the wall is (name, material, mass, later hp).
  A wall has no `polygon` of its own, so the two can't disagree, which is
  the kind of bug #345 was: the planner and the rules saw different walls.
- **A tiling with no T-junctions**, so a split stays local: the cut's ends
  go into the neighbours' polygons as vertices on their edges. In memory
  each edge knows its twin.
- **Planning:** a straight leg first (most mobs chase a player they can
  see), else A* over floor cells, with a budget on the search, then
  string-pulling through the edges crossed. So movement stays in floats and
  only the search uses cells. A mover fits through an edge by `clearance`
  and `width`, not by the edge's length, which would stop a wide mover in
  any open room tiled with small cells.
- **Alternatives considered:**
  - A grid laid over the area for A*: a narrow passage can lose every grid
    point, and a pinch point can gain points on both sides and look open.
  - Keeping the visibility graph and caching it per area: stale on every
    change to a wall.
  - One resource holding all of an area's cells: every change would
    publish all of them. One resource per cell publishes only what changed,
    and uses the versions, log and subscriptions that already exist.
- **Cost:** an area has about three times the resources (a 30 × 22 level
  of 0.8 m squares is 660 cells besides its walls), and `rock` cells show
  the floor plan behind the walls until fog of war filters them.
- **Start clean**, as on 2026-11-23 and 2026-11-24: stored walls have
  polygons and no cells, so a migration empties the games and the seed runs
  again (#353).

## 2026-11-25: a stash's radius is that of a pile of its items' mass

Supersedes the `0.1 × log10(grams)` stash radius (#336), which made a stash
of under 100 kg a metre across and a 1 kg one 60 cm.
- **A stash is a hemispherical pile** of its items' total mass at a bulk
  density of 1.6 t/m³ (`Stash.PILE_DENSITY`: rock at about 2.7 t/m³ packed
  to about 60%): `radius = cbrt(3 × mass / (2π × density))`, still clamped
  to [0.1, 1.0] m. 10 kg is 0.144 m, 100 kg 0.310 m, 1 t 0.668 m; up to
  about 3.35 kg is the 0.1 m floor, from about 3.35 t the 1.0 m cap.
- Physical, like #338's derived values, and it grows as the cube root of
  mass: eight times the mass, twice the radius.
- **One density for every pile**, not the items' own: items have no
  material yet. When they do, the volume can be the sum of each item's
  mass over its material's density, packed.
- Stored stashes keep their old radius until an item next joins, leaves or
  stacks onto them; no migration.

## 2026-11-25: items combine by mass, by count, or not at all

An item's `measure` now has three values, from its prototype:
- **`mass`** (rubble, dirt, guts, water): combine by adding grams.
- **`count`** (pebbles, arrows, consumables): combine by adding the count,
  and the grams.
- **`unique`** (weapons, armour, trophies): never combine; no `count`. Two
  short swords are two items. A stash may hold any number of them.
- **Anything of its own makes an item `unique`**: when items get stats,
  notes or enhancements of their own, an item that has any is `unique`
  whatever its prototype says, split off its stack and never rejoining it.
  No item has such keys yet, so this is the rule they will follow.
- **Alternatives considered:** a separate `stacks: bool` beside `measure`
  (a `mass` item that doesn't stack makes no sense, and a non-stacking
  `count` item is just one item, so one key covers it); comparing every key
  to decide likeness (fragile as keys are added; `unique` says it once).

## 2026-11-26: Postgres runs with jit=off (#400)
The resource store's SELECT (a correlated `children` subquery per row) on a
dive level of ~750 cells has a planner cost above the default
`jit_above_cost` of 100000, so every call JIT-compiled: a page of 200 cells
took 195 ms with JIT (168 ms of it inlining, optimisation and emission) and
6.7 ms without; loading a level's cells 203 ms against 7.7 ms. A dive level's
warm-up (~290 mouse turns) spent most of its ~450 ms per turn on that.
- **JIT is off for the whole server** (`args: ["-c", "jit=off"]` in
  `k8s-resources/platform/postgres.yaml`), not tuned by threshold: our
  queries are short and OLTP-like, so compiling never pays back.

## 2026-11-26: intelligence services get their turns by POST (#372)

Mobs are played by **intelligence services** (`microbe`, and more to come, one per kind of
intelligence): world POSTs each turn to the service with the entity's surroundings, and the
response is the intent. npc services that simulate players (`orbital`, `diver`) keep the
WebSocket turn feed and the intents API. The contract is in `actions.md`, "Intelligence
services".
- **Why:** a mouse's turn cost three hops and a full re-read of its area (~750 cells) every
  time, about 0.45 s, so a level's 500 s warm-up took two minutes. With the context in the
  request, the service decides without reading anything, holds no socket or state, and can run
  as N replicas behind a Service (#342). It also lets world call the service from inside one
  advance (a warm-up as one advance, next).
- **The context is what's in range:** the cells within `range` of the entity, the entities
  within it, and the walls of those cells. `range` is per service in world's registry for now.
  Items aren't sent: no mob holds any yet.
- **No timeout, no default:** a failing service is retried with backoff forever, and its areas
  pause. A game whose mice stop thinking should stop, not have world invent their moves. #151
  (a turn timeout) now covers players and npc services only.
- **The outbox for turns not decided in an advance:** a turn is a row written in the advance's
  transaction, dispatched after commit with `FOR UPDATE SKIP LOCKED`, so a crash or a second
  world pod neither loses nor doubles a turn. Turns are normally decided inside the advance
  (next entry); the outbox is where one goes when that can't happen.
- **world is a service principal** (`sub=world`, #374) only to call intelligence services; it
  posts no intents of its own, and applies the answers as the entity's controller.
- **Alternatives considered:** keeping the turn feed and sending the context in the `turn`
  frame (still a socket per service, still a hop through subscriptions, and no way to call it
  inside an advance); sending the whole area (~500 kB per mouse turn on a dive level).

## 2026-11-26: an advance decides mobs' turns inside it

An advance now runs to the next turn of a player or an npc service. Every mob turn on the way is
asked of its intelligence service inside the advance, and started like an intent
(`actions.md`, "Game time"). A level's warm-up is then one advance in the crossing's transaction.
- **Why:** a player only needs the game state at their own turn. Committing, publishing and
  dispatching once per mob turn made a dive level's warm-up 290 transactions and about two minutes,
  and spectators watched the mouse move in real time before the diver arrived. One advance
  per player turn is one transaction, one log chain and one change batch. The client replays the
  mobs' moves from the log at its own pace.
- **Not specific to warm-up:** the rule is the same for every advance; a warm-up is an advance
  that ends at game time.
- **The area lock is held while a service thinks.** That reverses "no area lock is held while a
  service thinks" from the entry above. Nobody else could act in the area meanwhile anyway: it
  isn't their turn. Reads are not blocked. A wall-clock budget per advance (`advance-budget`, 10 s)
  bounds it. Past it, the advance commits and the outbox goes on.
- **Failures fall back to the outbox:** a service that fails (or failed within the last
  `timeout`) ends the advance at its turn, and the dispatcher retries with backoff as before. An
  answer the start rules reject ends it too, logged as blocked.
- **The engine stays pure:** it takes a `Decider` (`libs/world-engine`); world's makes the HTTP call.
- **Alternatives considered:** a warm-up-only loop in `Crossing` (the same latency remains on every
  ordinary turn of a level with mobs); batching several mobs per request (later, if a level has
  many mobs; one call per turn keeps each decision on the state it sees).

## 2026-11-26: a warm-up is 100 s, not 500 s

`Arrive.WARM_UP` is now 100 s: a new level starts at game time minus 100, and a returned one at
game time minus min(time away, 100). This replaces the 500 of `decisions-settled.md` (2026-11-16,
crossing areas).
- **Why:** with every mob turn decided inside the advance, a dive level's 500 s warm-up is one
  advance of ~290 mouse turns at ~20 ms each, about 6 s. That's close to `advance-budget` (10 s), and
  past the budget the rest runs from the outbox, so spectators see the level before the arrival.
  100 s is about a fifth of that time, with the mobs still spread from where generation put them.
- **For now:** if levels need more settling, or calls get cheaper (several mobs per request), the
  warm-up can grow again.

## 2026-11-26: one NOTIFY channel per area, and turn notices on their own (#378, #342)
Every subscriptions pod used to LISTEN on one channel, `resource_changes`, and
get every area's batches, so more pods meant more work and no more capacity.
- **A channel per area.** A batch goes on `area_<32 hex>`. A subscriptions pod
  LISTENs on an area when its first socket subscribes, before loading the
  version map (so nothing falls between), and UNLISTENs when the last leaves.
  A pod that starts mid-game builds its maps the same way. Two pods serving
  one area stay correct, which is normal while pods are added or removed.
  Routing an area's sockets to one pod (consistent hashing on `?area=`,
  #379, #380) then only saves duplicate work.
- **Turn notices on `resource_turns`.** The turn feed joins a controller to
  areas it is up in, including areas none of the pod's sockets is in, so it
  can't depend on per-area channels. A batch that changes who is up also
  sends a small notice with the new `controller` (or "read the area", for a
  reload or a stub of the area). Every pod gets every notice. That is one
  small payload per turn instead of every batch.
- Channel names come from the id (the UUID's hex digits, or a SHA-256 prefix
  for other ids) and are checked before they go in a `LISTEN` statement.
  LISTEN can't take a bind parameter.
- **Rollout.** world and subscriptions deploy separately. Until both run the
  new code, batches go to channels nobody LISTENs on. As with any lost
  NOTIFY, clients catch up through the checksum and the logSeq chain, and
  controllers resubscribe when subscriptions restarts.
