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
