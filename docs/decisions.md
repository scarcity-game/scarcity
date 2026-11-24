# Decisions and their reasons

Why things are the way they are. The rules themselves live in
[`AGENTS.md`](../AGENTS.md) and the other `docs/` files; read this when you
want to change one. Entries say what holds now: a revised decision is
rewritten in place with the date of the revision, not appended. Superseded
history is in git.

## 2026-11-09: resource vocabulary
`game ⊃ area ⊃ entity ⊃ item`, one set of words across code, APIs and docs;
"zone", "map", "mob" and "level" had been used inconsistently. A **user**
(login) is distinct from a **character** (a `player` entity): permadeath must
never delete an account.

## 2026-11-09: change notifications over Postgres `LISTEN/NOTIFY`
Published in the writing transaction, so a rolled-back change is never seen.
RabbitMQ and NATS were rejected as stateful infrastructure we don't need yet,
and durability isn't needed because version checks catch anything lost.
Services go through `libs/resource-model`, not `pg_notify`, so the transport
can move to Redis Streams later (many `subscriptions` replicas, replay)
without touching every service.

## 2026-11-09: observability (#18)
OpenTelemetry with W3C `traceparent`, OTLP to the Grafana stack run by the
k8s agent. Micrometer Tracing with the OTel bridge rather than the Java agent,
so instrumentation is ordinary code that tests can assert on.

## 2026-11-10: integer versions, no hash
A version bumped by one per change under the row lock is a gapless sequence
that detects missed updates. A hash would make clients canonicalise JSON with
floats in it, and float formatting would cause false mismatches. The parent's
version covers its child list, so adding or removing a child is detectable.

## 2026-11-10: one batch per area per advance, carrying the new state
The cost that matters is the follow-up `GET`s, not the fan-out. A batch
carries each new state, so a client at version N applies N+1 without a
request and only reads on a gap. A batch over NOTIFY's 8KB limit is split or
sent without patches. Estimate: 40 acting + 100 watching characters at one
action per second is about 5,600 messages/s, within one `subscriptions`
instance. A patch is the full state for now; deltas will be marked
explicitly when they come (2026-11-11).

## 2026-11-10: generic resource API
List by filter, list children, get one, defined in `libs/resource-model` so
any service can serve it. The client loads layer by layer; `ETag` is the
version.

## 2026-11-10: the area is the unit of ownership
An advance is one transaction under the area's lock, published as one batch,
so clients never see half an advance and an area's events have one order.
Spreading areas over `world` instances (leases) is deferred.

## 2026-11-11: the digest comes from the server
The server sends a digest of its versions on subscribe and every ~30 s and
the client compares, rather than the client sending its version map. The
server stays the source of truth and the client keeps one rule.

## 2026-11-11: every service Dockerfile copies every module's pom
`mvn -pl <module> -am` needs every module in the root pom present; adding a
module without its `COPY` line in every Dockerfile broke CI twice.

## 2026-11-12: actions and effects
Resources change state only through actions: one at a time per resource, each
with a duration in game time and its effect at the start (committed, then
recovery) or the end (a wind-up that can be cancelled). An **effect** is an
instant link in a chain (throw → line attack → projectile → damage → break →
rubble) with its own log entry and cause; actions take time, effects never
do. Continuous movement is a chain of short actions, in the roguelike
tradition, and clients may animate in between because the action in progress
is part of the state. A behaviour can pick among several actions (`random`
records its pick as a draw). Resources are created and destroyed during an
advance, so a change has `children` and `deleted`, a digest is authoritative
for what exists, and a destroyed change carries no version (2026-11-13).

## 2026-11-12: an area log of every change, with causes
The state says what is true; players are promised what happened. So every
write gets an append-only log entry in the same transaction (`log.md`).
Entries carry resulting state, so a client replays without game rules and old
logs survive rule changes. `cause` and `root` make an action and its
consequences a tree. Snapshots plus retention bound the size and let a replay
start anywhere. The log records `created`/`destroyed`, not child lists.

## 2026-11-13: the log is followed by a pushed position
Each batch carries `logSeq`, the log's new end, and the client reads the
entries it lacks from `world`. Pushing entries would mean `subscriptions`
reading `world`'s tables or full states inside an 8KB notification.

## 2026-11-13: game time passes when someone acts; orbital plays the test area (#104)
Contract: `actions.md`, `action-types.md`, `log.md` (kind `intent`), `resources.md`.
- **Event-driven time.** An area has a plain `time`; an intent runs one
  advance: start the action, process events until a controlled resource is
  idle, stop. No wall clock, no scheduled loop, no idle ticks, nothing to
  catch up after a restart. (This replaced a wall-clock anchor with
  pull-back, whose only reason was the unattended test area.)
- **A service plays the test area.** `orbital` plays the orbiter through the
  same intent path a player uses, so the live test area tests the real loop.
  When orbital is down the area stands still, and that is accepted.
- **Controllers or behaviours, never both**, so "who decides" has one answer.
  NPCs keep behaviours and act during a player's action.
- **Decisions belong to the controller.** The rules take points (`step` to a
  point, `throw` at a point); the orbit lives in orbital's config. A
  controller's `reported` choice is logged verbatim, not trusted or checked.
- **API keys checked by world**, not swapped for JWTs: one service needs a
  credential and only `world` checks it, so `auth` stays untouched.
  (Superseded the same day: "service tokens from auth", below.)
- **The world module splits into libraries** (`AGENTS.md`, "Modules"): the
  rules test in seconds without Docker, orbital reuses the model, and the
  build enforces that the engine never touches the database. The cost is
  more poms and `COPY` lines.

## 2026-11-14: how agents work
Revises the process of 2026-11-11 to 2026-11-13 (module leads, local-model
workers, the ticket board). It didn't work out: a lead spent 70k to 220k
tokens per task supervising the local model, which paid for most of what the
free workers saved. Now complicated work is done by the root agent in the main
checkout, and smaller work by one Sonnet developer in a worktree that tests,
opens the PR and answers review comments itself. The human reviews every PR
and CI gates the merge, so the root agent doesn't repeat the developer's
checks; the human moves work up when the process fails. What still holds
from before: a worktree per subagent (a shared checkout once swept another
agent's files into a commit that deployed), no `commit -a`, and small tool
output (it was nearly all of the agent cost).

## 2026-11-14: shared libraries, not copies
The observability wiring was copied into auth, world, subscriptions and
orbital, identical but for the package; it is `libs/observability`, a Spring
auto-configuration a service gets by depending on it. Configuration checks use
Bean Validation (`@Validated` with `jakarta.validation` constraints) rather
than hand-written checks in each module, so they read the same everywhere and
report every bad key at once. (Review of #145.)

## 2026-11-14: service tokens from auth
Revises "API keys checked by world" (above). orbital has to use the
subscriptions WebSocket as players will, so it needs a credential that
subscriptions accepts too. Rather than teach every service to check keys,
auth exchanges a key for a 15-minute JWT (`kind=service`, `sub=<principal>`),
and every service keeps verifying one kind of credential against the JWKS.
Keys live in one place (auth), and a leaked token expires on its own. The
`kind` claim keeps a service token from passing for a user, and a user token
for a service. (#147, review of #145.)

## 2026-11-14: one resource up at a time, told over the socket
A controller (orbital now, players later) connects, waits to be told it is
up, then acts. It doesn't post on a timer. Whose turn it is is game state, so
the engine writes it into the area (`up`, `pending`), where it is versioned,
logged and published with the advance at no extra write.
- **Never two decisions at once.** Several controlled resources can be idle
  at the same time; they queue by `since`, then id, and only the first may
  act (others get a 409). Simultaneous events already resolve by time, then
  id. So every outcome in an area has exactly one order, and there's no
  moment where two players could move at once. The cost: a slow controller
  holds up its area, so a turn timeout will be needed with several players.
- Subscriptions turns `up` into a `turn` frame for that controller's socket
  only, repeated with every digest so a lost NOTIFY can't strand it;
  duplicates are harmless because `ifVersion` makes the second intent a 409.
(#148, review of #145.)

## 2026-11-15: fields and constructor parameters are named for their class
A non-static field, or a constructor parameter (a record component is one), whose
type is defined in this project is named for the class: `AreaState areaState`,
`ActionSpec actionSpec`. Locals are free. Where a class has two of one type
(`from` and `to`, a projectile's `state` and `debris`) the roles stay.
- **The wire format followed**, because record components are JSON keys: the
  resource envelope `type` is `resourceType`; an entity's `kind` is `entityKind`,
  its `action` is `actionInProgress` (with `effect` now `timing`); a behaviour's
  and an intent's `action` is `actionSpec`; log entries use `controller`,
  `actionSpec`, `actionInProgress` and `loggedEffect` for `by`, `intent`, `action`
  and `effect`; `create` takes `entityState` and `actionSpec`, `break` and `fly`
  take `entityTemplate`, a projectile has `damageRoll`. Query parameters (`?type=`)
  and the orbital configuration keys did not change. The key `kind` of a log
  entry, a behaviour and a controller did not change.
- **Stored data is dropped** (migration V5): the old keys cannot be read and the
  game is test data. The world service seeds the test area again. (#174.)

## 2026-11-15: world writes a missing queue at startup
An area stored with no `up` (before turns existed) was never told it could
advance: admission already recomputes the queue, but subscriptions sends a
`turn` only from the stored `up`. `Engine.refresh` writes the queue with one
`queue` log entry and no game time, and `world` runs it for every area at
startup (`QueueSweep`). Writing on load or on read was rejected: a read must
not write, and the change has to be published like every other. (#158.)

## 2026-11-16: crossing areas and making levels
Levels are made **lazily** when stairs are first used, deterministically from
the game seed and the depth. Taking stairs is the **`traverse`** action (the
name in [#168](https://github.com/scarcity-game/scarcity/issues/168)).
- **Game time and area time.** Game time is the game's and is what clients
  show; each area has an area time. They match wherever a player is, and
  diverge only during warm-up (a new area) and catch-up (a return). A
  warm-up never adds to game time. A new area starts at game time minus 500
  and runs to game time; a returned one at game time minus min(time away,
  500). Rebasing event times was rejected: the offset is the area's own
  clock.
- **One transaction, a placeholder.** `traverse` removes the entity from the
  old area and puts a placeholder in the new one in one transaction. The
  entity is always somewhere, so a crash between "removal" and "arrival" is
  not a state; the earlier plan of two locks with the entity in no area was
  dropped. A first visit locks only the old area (the new one is made inside
  the transaction); a return locks both in area id order.
- **Warm-up is ordinary advances.** Mob and npc services act over the intents
  API at full speed: they decide from the context in memory, so there is no
  wall-clock wait, and one service may decide for many mobs. So no in-engine
  behaviour kind is needed, and the climb is only as asynchronous as the
  services are quick. Npcs that simulate players are the exception. The
  player arrives when area time reaches game time. A service being down stalls
  the arrival; a turn timeout stays a follow-up
  ([#151](https://github.com/scarcity-game/scarcity/issues/151)).
- **Visibility.** Subscriptions publish to the area an entity is in, so the
  player sees the new level only from arrival. A wait screen waits until the
  load time shows it is needed.
- **Open:** several players in areas on different clocks; one player per game
  is assumed. (#167.)

## 2026-11-17: a turn feed for controllers that play in every area
A controller service can subscribe to its turns wherever they are
(`{"type":"subscribe","turns":true}`, `websocket.md`) instead of to named
areas (#170).
- **Why.** Levels are made lazily, so a mob service such as `microbe`
  can't know their ids, and an arrival waits for its mobs (2026-11-16, an
  area with no player or npc stands still). Polling the area list would
  delay every new level by the poll.
- **How.** subscriptions already sees every batch on `LISTEN`; a batch whose
  area state names an active entity the socket controls joins the socket to
  that area, an ordinary subscription. On subscribe it lists the areas once.
- **Rejected:** a list-and-poll controller (no contract change, but a delay
  per level and a poll); turn frames without joining the area (no version
  map for `version`).
- Joined areas stay subscribed for now; with many levels per game the feed
  may need to leave areas nobody is in.

## 2026-11-16: an area with no player or npc stands still
An area runs only while a player or an npc is in it, or one is arriving
(a placeholder) ([#190](https://github.com/scarcity-game/scarcity/issues/190)).
- **Why.** Mob services such as `microbe` would otherwise be asked to play
  every level nobody is on, forever, and the game would pay for areas no one
  sees. Mobs alone are not a reason to run.
- **Leaving stops it.** When the last player or npc leaves, the advance stops
  at the departure, area time stops, and nobody is up (`activeEntity` absent,
  `pending` empty); an intent there is a 409.
- **Arrival is the exception.** Warm-up and catch-up (up to 500 s,
  #169) are the only time an area runs without one. They stop for an idle mob
  like any advance and its service acts over the intents API, which replaces
  the first warm-up of #168 that ran to game time whoever was idle.
- A missing mob service still stalls an arrival (#151).

## 2026-11-16: resources are a typed hierarchy and flat JSON
The envelope `{id, resourceType, version, children, state}` with an untyped
`state` is gone. A resource is an abstract `Resource` (`id`, `version`,
`children`), extended by `Game`, `Area` and `Entity`; `Entity` by `Fixed`
(`Stairs`), `Mob`, `Npc` and `Player`. It is **flat JSON** with `resourceKind`
(and for entities `entityKind` and `prototype`) choosing the class, on the
wire, in change notifications and in the log's `changes[].state`. The server
reads untrusted JSON only through that deserializer, which rejects unknown
kinds and prototypes. Entities are made from **prototypes**, named JSON files;
`mobile` is retired (`mob` is the moving kind), and `overlap` and `damageable`
are booleans on `Fixed`. Abstract classes rather than sealed interfaces, as
entities and items will number in the thousands. The database keeps its
columns; `resources.state` holds the JSON minus `id`, `version`, `children` and
`resourceKind`. Stairs have no `from` and a null `to` means the other level is
not made yet.
- **Nothing here is historical**: the wire format changed in every module at
  once, migration V7 empties the world tables and the seeder re-seeds.
- **Kept out, on purpose:** items (no `Stash` class), deltas and child
  add/remove in notifications, and size limits on a change are
  [#186](https://github.com/scarcity-game/scarcity/issues/186). Each change is
  still the whole resource. (#185.)

## Future ideas the design stays open for
Several players in one area (an advance stops when any controlled resource is
idle), interest management and fog of war, deltas in patches, Redis Streams
for fan-out, area leases across `world` instances, and services that control NPCs at different levels of intelligence through
the same intents API.

## 2026-11-15: the area's `up` is `activeEntity`
The area state's `up` (the one controlled entity that may decide) is now
`activeEntity`; `pending` stays. `up` read like a direction or a status next
to the other state keys, and the stairs work adds code that talks about the
entity whose turn it is. It is a wire name: world-model, engine, the `turn`
frame's source in subscriptions, the docs, and the rejection reason
`NOT_UP` (now `NOT_ACTIVE`; the HTTP status is still 409).
- **Stored areas are rewritten** (migration V6: the key is renamed, the version
  goes up). **The area logs are dropped** in the same migration, because their
  snapshots and patches carry the old key, as V5 did for #174; an area gets a
  new log, starting with a snapshot, at its next advance. (#164.)

## 2026-11-18: stairs down and stairs up are two prototypes
Stairs were one prototype, `stairs`, and nothing said which way they led: at
depth 0 the stairs up and the stairs down both had a null `to`, and taking
either made the level below. The diver (#171) has to find the stairs down,
and a client wants to draw them apart. So stairs are two prototypes of the one
`Stairs` class, `stairs-down` and `stairs-up`; the prototype is the direction.
- **Not a field.** A `direction` key was the other option; the prototype
  already names the kind of fixed thing an entity is, and a prototype each
  gives each its own defaults and name. Reading by display name was rejected.
- **Stairs up that lead nowhere** (a null `to`: depth 0, the only stairs up
  not made by a crossing) are rejected by `traverse` ("no level above").
- **Migration V8** renames the stored stairs by their seeded names; the area
  logs keep `stairs` as history.

## 2026-11-18: SI units, and physical attributes on every entity (#206)
The entity panel (#79) shows what an entity is, and sprites (#207) are sized
by it, so every entity has to say how big and heavy it is, in units a player
can picture.
- **SI units.** Lengths in metres, mass in grams, time in seconds: 1 distance
  unit is 1 m and 1 time unit is 1 s, so speed is m/s. Grams rather than
  kilograms because the small things (a mouse is 20 g, a pebble 30 g) are
  most of what is counted.
- **On `Entity`, not on a kind.** `mass` and `radius` are on every entity and
  required when it is stored; `overlap` and `damageable` moved up from
  `Fixed`, and `hp` is there exactly when it is damageable. The placeholder is
  the one entity that isn't physical (`Entity.physical()`); it only holds a
  place. Prototypes carry the values; nothing in a client makes them up.
- **Overlap is the rule, not the lack of a radius.** Before, stairs and rubble
  had no radius, and that was what let others walk over them. Now they have
  one, and `step` and `line-attack` pass over anything with `overlap`.
- **Reseed, not migrate.** The stored entities lacked the keys (a mouse had
  `hp` and no `damageable`) and the world is test data, so migration V9
  empties the schema as V7 did, and world-seed seeds afresh.
- Later physics (friction, volume, material) is out of scope.
## 2026-11-18: one shared Maven dependency layer for every image
When #209 added a module, every service Dockerfile changed above its
`dependency:go-offline` layer, so every image job missed its cache at once and
downloaded everything from Maven Central in parallel; Central answered 429 and
the deploy failed twice. Each Dockerfile resolved only its own module's
dependencies, so the layers differed and couldn't be shared.
- **One stage, the same bytes everywhere.** Every Java Dockerfile starts with
  the `deps` stage from `config/docker/maven-deps.Dockerfile` (all poms, the
  whole reactor), kept identical by `config/docker/maven-deps.py`, which CI's
  `plan` job runs. A `deps` job builds it once into the `maven-deps` cache
  scope before the images and the acceptance tests' stack, which read it.
- **The lifecycle, not `go-offline`.** `go-offline` missed test-scope and
  plugin dependencies, so the package step went to Central too. Running
  `package` over the poms alone (Boot repackage skipped) resolves what the
  builds use; every service then packages with no network.
- **Rejected:** a base image pushed to the registry (PR builds would need to
  push, and the deploy would depend on it); Maven retry settings alone (they
  would make the parallel downloads slower, not fewer); a BuildKit cache mount
  (the GitHub Actions cache doesn't keep mounts).
- Supersedes the 2026-11-11 rule's mechanics: the COPY lines now live in one
  place.
## 2026-11-19: setting is low fantasy, underground (#204)
Sprites (#207) can't be drawn, and prototypes and the client can't be styled,
until the setting is fixed. The text is in [`setting.md`](setting.md).
- **Fantasy, not science fiction.** A delve into an old, deep dungeon where
  everything is scarce: light, food, health, time. Grim, practical and a
  little wry, DCSS rather than high epic. Magic exists but is costly and
  physical; the orbiter's thrown stones are the model. Deeper means older and
  stranger.
- **Palette direction.** Stone greys and earth browns for the world, torchlight
  amber as the warm accent, muted natural hues for creatures, one cool accent
  (teal or violet) reserved for magic. The client gets dark backgrounds.
- **Categories first.** A sprite shows its category (`humanoid`, `rodent`,
  `stone`, `pillar`, `rubble`, `stairs`) at a glance; variants differ by
  palette and overlays only. `content/sprites` is the module.
- **Not renamed.** Prototypes and actions keep their names. A clash with the
  setting is an issue, not part of this decision.

## 2026-11-18: list resources by game, not `parent` in the JSON (#236)
Finding where a game's player or npc is took one request per area. Options:
expose `parent` in resource JSON, or a `game=<id>` list filter. We chose the
filter: `GET /api/world/resources?type=entity&entityKind=player&game=<id>`.
It leaves the resource wire format (and the change notifications) unchanged,
and it takes one request however deep the game is. The query is one SQL
statement on the existing `parent_id` column and `(parent_id, type)` index, no
new column or index. `game` is now a reserved query parameter, not a state
filter key. Items are not covered yet (the world has none).
## 2026-11-19: Spring Boot 4.1 (#246)
Boot 3 was only the bootstrap default (2026-11-09), never a decision. Every
3.5.x pins Flyway 11.7.2, which doesn't support PostgreSQL 18 (#3); Boot 4.1
brings Flyway 12, Spring Framework 7 and Jackson 3.
- **In steps.** Golden tests of the wire format first (#252), then Boot 4.1
  still on Jackson 2, then Jackson 3. Until then `spring-boot-jackson2` and
  `spring.http.converters.preferred-json-mapper: jackson2` (in
  `libs/observability`) keep Spring's mapper and the HTTP converters on
  Jackson 2.
- **A changed golden is adapted to, not configured back.** When an upgrade
  changes what goes on the wire, we take the new library behaviour and adapt
  the clients. Our strict reading rules (`WorldJson`) are model rules and stay.
- **Modular starters**, not `spring-boot-starter-classic`: `webmvc`, `flyway`,
  `security-oauth2-resource-server`, and per-technology test modules.
  Tracing needs `spring-boot-micrometer-tracing-opentelemetry`; not the
  OpenTelemetry starter, whose OTLP metrics registry would push metrics we
  scrape with Prometheus.
- **Renamed configuration.** The OTLP traces endpoint is
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` (was
  `MANAGEMENT_OTLP_TRACING_ENDPOINT`, which Boot 4 ignores).
## 2026-11-19: Jackson 3 (#246)
The last step of the Boot 4.1 upgrade: `tools.jackson` everywhere, and no
Jackson 2 `databind` left (`jackson-annotations` 2.x stays; Jackson 3 uses it).
- **Key order is Jackson 3's.** Keys in `@JsonPropertyOrder` come first, the
  rest alphabetically. The goldens changed only in key order (checked as
  parsed trees), and clients don't depend on it.
- **Reading rules are explicit.** Jackson 3 accepts unknown keys by default;
  `WorldJson` sets `FAIL_ON_UNKNOWN_PROPERTIES` with the other strict
  features, because they are model rules, not defaults.
- **Exceptions are unchecked** (`JacksonException`), so no `try`/`catch` is
  left that only turned a checked exception into an unchecked one.

## 2026-11-20: movement is a channel, written at every event (#188)
A `step` used to change the position only at its end: in between, the mover
was where it started, whatever happened meanwhile, and the client could only
jump it at the end. Now `step` has a fourth timing, `channel`: the state
changes over the whole duration.
- **Written at every event** (the human's choice, over a position derived
  from the action on read). Before the first event at each game time while a
  channel runs, the engine moves it on to that time and logs an
  `action-progress` entry with the new position. The stored state and every
  rule read the position as it is, with no interpolation anywhere, at the cost
  of one entry per mover per event time. One write per resource per advance
  still holds: the entries all carry the advance's one version.
- **Obstacles are checked part by part**, each part cast against the area as
  it is at that event, so something that moves into the path stops the mover
  at its edge. A stopped channel ends then (its `end` moves up), so its
  controller decides again at once instead of pushing against the obstacle
  until the planned end. Between two events nothing is checked: two movers
  can cross paths between events without meeting, as before.
- **The end does the rest.** `Action.progress` moves part of the way;
  `apply` at the end moves the rest and gives the result, so a step lands
  exactly on `to` (no float drift from summed parts). A channel ending at the
  event about to run is not moved separately first: no extra entry for a
  step that nothing else interrupted.
- Not yet: interruption by being hit (#133), and `fly` as a channel
  (projectiles still land at their end).


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

## 2026-11-20: deltas, a version checksum and the logSeq chain (#186)
Generated areas (#234) put hundreds of wall entities in an area. A batch
carried each changed resource whole, including the area's full `children`
list. At about 46 bytes per id that list overflows NOTIFY's 7,900 bytes at
around 170 entities, so every advance that made or destroyed something would
send the area as a stub and make every client `GET` it. The 30 s digest also
listed every id and version.
- **The socket carries changes, not state.** A batch holds deltas: an update
  (`set`, `unset`, `childrenAdded`, `childrenRemoved`, applied only to
  version − 1), `created` (id and version only), `deleted` and the stub.
  Loading anything is a `GET`. This supersedes "carrying the new state"
  (2026-11-10).
- **Home-rolled deltas, not RFC 6902.** Resources are flat, so top-level keys
  are enough, with a changed value sent whole. The Java libraries for RFC 6902
  are built on Jackson 2 and we're on Jackson 3 (#255), and the server only
  ever produces deltas, which is a small diff of top-level keys.
- **Creations are ids.** A crossing sends one `reload` for the area the
  traveller arrives in, and the client loads the area and pages its
  children. On a first crossing everything in it is new. On a return the
  placeholder is written outside an advance, so no delta could carry it
  without breaking the version rule, and returns are rare. Creations inside
  a running area (a smoke bomb's forty clouds) are `created` plus a `GET`
  each, which is fine for now. A batch-read endpoint can come later if that
  hurts.
- **A checksum instead of the digest.** The sum mod 2³² of
  `low32(id) × version`. It's order-independent, so the server keeps it
  incrementally and neither side sorts. It's about 60 bytes every 30 s,
  whatever the area's size. On a mismatch the client reads
  `GET …/{area}/versions` and refetches what differs. This replaces the
  digest (2026-11-11). "No hash" (2026-11-10) still holds: the checksum is
  arithmetic on ids and versions, never on JSON.
  - **Alternatives considered:**
    - Hashing each `id:version` pair (FNV-1a 64, summed mod 2⁶⁴) works, but
      needs a hash and BigInt in the browser.
    - A plain sum of versions misses offsetting errors, such as a missed
      update and a missed delete in one lost batch.
    - Multiplying by the id's random low 32 bits ties each version to its
      id, so those errors and swapped versions are caught, in 32-bit integer
      arithmetic on both sides.
    - A Bloom filter of id and version would show *which* entries are stale
      without the versions read. But a false positive hides a stale resource
      until the set changes, and it can't show resources the client lacks.
  - **Known weak spot:** the seeded fixed ids (`…0001`, `…0002`) have tiny low
    bits, so errors there could in theory cancel. It takes several unlikely
    errors at once, and generated ids are random.
- **The logSeq chain.** Each batch carries `fromLogSeq` and `logSeq`, so a
  missed batch shows at the next one instead of at the next checksum. A
  split batch numbers its parts, so the chain moves on after the last part.
- **Reserved frames:** `chat` and `system` (#261), not built.

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
