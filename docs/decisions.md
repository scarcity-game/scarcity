# Decisions and their reasons

A dated log of why things are the way they are. Nothing here is a rule you
need to do a task; the rules live in [`AGENTS.md`](../AGENTS.md),
[`resources.md`](resources.md) and [`websocket.md`](websocket.md). Read this
when you want to change one of those rules or wonder why it exists. Newest
last. Add an entry when a cross-module decision is made or revised.

## 2026-11-09: resource vocabulary
`game ⊃ area ⊃ entity ⊃ item`, with entity kinds `mobile`, `fixed`, `player`.
One set of words across code, APIs and docs; "zone", "map", "mob" and "level"
were in use inconsistently and are banned for these things. A **user** (login)
is distinct from a **character** (a `player` entity): permadeath must never
delete an account.

## 2026-11-09: change notifications over Postgres `LISTEN/NOTIFY`
Publish in the writing transaction so a rolled-back change is never seen.
RabbitMQ and NATS were considered and rejected for now as stateful
infrastructure we don't need yet. Durability is not needed either, because the
version checks catch anything lost. Services go through an interface in
`libs/resource-model` rather than `pg_notify` so the transport can move to
Redis Streams later (many `subscriptions` replicas, high fan-out, replay after
disconnect) without touching every service.

## 2026-11-09: observability stack (#18)
OpenTelemetry everywhere with W3C `traceparent`, exported over OTLP to the
Grafana stack (Tempo, Loki, Prometheus, Grafana) run by the k8s agent. Java
services use Spring Boot's Micrometer Tracing with the OTel bridge, not the
OTel Java agent, so instrumentation is ordinary code and tests can assert on
it. Spans and logs are operational data; game logs (distributions, rolls) are
a separate player-facing record.

## 2026-11-10: integer versions, no hash
A per-resource version that goes up by one per change, bumped in the same
transaction under the row lock, gives a gapless sequence that detects missed
updates. A hash was rejected because clients would have to canonicalise JSON
with floats in it, and float formatting differences would cause false
mismatches. The parent's version covers its child list so that adding or
removing a child is also detectable.

## 2026-11-10: one notification per area per tick, carrying patches
The cost that matters is not the fan-out of notifications but the follow-up
`GET`s. So a notification carries the new state (`patch`) and a client at
version N that receives N+1 applies it without a request; `GET`s happen only on
a version gap. A batch over NOTIFY's 8KB limit is split, or sent without
patches. Fan-out estimate: 40 acting + 100 watching characters at one action
per second is about 5,600 messages/s, within one `subscriptions` instance.
Later: interest management (filter each batch per client by distance or line
of sight), which also gives fog of war.

## 2026-11-10: generic resource API, three operations
List by filter, list children, get one. Defined in `libs/resource-model` so
any service can serve it; `world` is the first. The client loads a game layer
by layer (game → area → entity → item). `ETag` is the version string.

## 2026-11-10: the area is the unit of ownership
One `world` instance owns an area and applies its actions one at a time:
deterministic order and no locking inside an area. A tick is one transaction,
published as one batch, so clients never see half a tick. Spreading areas over
instances (lease rows in Postgres) and unloading idle areas are deferred. How
game time works (action durations, how mobile entities choose actions) is still
undecided; it comes after the test area.

## 2026-11-11: the digest comes from the server
The earlier design had the client send its own version map on subscribe. It
was replaced: the server sends a digest of its current versions on subscribe
and every ~30 s, and the client compares. The server is the source of truth
and the client logic stays one rule.

## 2026-11-11: a patch is the full state, for now
Deltas were deferred. `world` always sends the full new `state` and the client
replaces it. Deltas will be marked explicitly when they arrive.

## 2026-11-11: a worktree per unit of module work
Several agents shared one checkout and a commit from one of them swept up
another's unfinished files; `main` auto-deploys, so that reached the cluster.
Since then every module lead runs in its own fresh worktree (`isolation:
"worktree"`), commits only to its branch and never pushes; the repo agent
reviews, fast-forward merges and pushes. `git commit -a` and `git add -A` are
banned in the main checkout.

## 2026-11-11: all agent roles on Sonnet; keep tool output small
A usage report showed the module-lead subtree as 99% of cost. The lead's own
context was the small part; the bulk was developer and tester tool output
(full Maven logs, Docker and curl output, npm runs). Moving the lead to Haiku
was rejected because the lead carries the judgement in the develop-test loop
and a failed loop costs more than it saves. Instead: every role runs Maven
with `-B -q` through `grep`/`tail`, reads surefire summaries, tails logs and
never `cat`s big files; a lead stops after two failed tester rounds and
reports. The root `AGENTS.md` was split (this `docs/` directory) so each spawn
reads less.

## 2026-11-11: the test area milestone
Built in the order `libs/resource-model` → `services/world` →
`services/subscriptions` → `clients/web`, and live: one game, one 10×8 room,
a fixed pillar and a mobile entity orbiting it with a new float position every
second, streamed to the browser over the WebSocket with gapless versions and a
`traceparent` on every batch. Issues #9, #29, #30, #31.

## 2026-11-11: every service Dockerfile copies every module's pom
Service images build from the repo root with `mvn -pl <module> -am`, and the
Maven reactor needs every module in the root pom to be present. Adding a Java
module without adding its `COPY <module>/pom.xml` line to every service
Dockerfile broke CI twice. The rule is in `AGENTS.md` under Defaults.

## 2026-11-12: local inference for development and review; the lead is the gate
A LAN box now serves gpt-oss-20b, and Claude Code can run against it
(`claude-local`, started as herdr `local` workers). Developer and tester work
was nearly all of the agent cost (see 2026-11-11), so that work moves there:
the module lead, still on Sonnet, splits a task into single-goal steps and
feeds them to a local worker, then has a fresh worker review the result. The
model is weak (about one claim in three was wrong in testing) and its context
is at most 128k, 64k by default, so three things changed with it. The lead
runs every build and test and reads every diff itself, because a worker's
verdict cannot gate a merge; this gives up the independent Sonnet tester in
the normal path, and the lead's own verification replaces it. Workers get
their whole brief in the task and don't read the repo docs. Only one module
lead runs at a time, so a worker has the box to itself. The Sonnet developer
and tester remain as the fallback when the box is down, a step fails twice,
or the task is too subtle. Workers edit files but don't run shell commands
(those prompt); the lead runs them.

## 2026-11-12: actions and game time
Resources change the game state only through actions: one at a time, each with
a duration in game time and an effect applied either at its start (committed,
then recovery) or at its end (a wind-up that can be cancelled). Contract:
`resources.md`, "Game time" and "Actions".
- **The clock is an anchor, not a counter.** Deriving game time from the epoch
  (as the first orbiter did) makes everything jump forward after downtime;
  writing the clock every tick bumps the area's version every tick. An anchor
  `{gameTime, wallTime, rate}` is written only when the relation to the wall
  clock changes, and clients can compute the time themselves.
- **Pull-back instead of catch-up.** After a stall the clock is re-anchored to
  the earliest due event (limit: 5 time units of lag), so a restart neither
  skips nor replays game time, and no "last tick" has to be persisted.
- **Ticks are not turns.** A tick is only the transaction and the batch. Events
  carry exact game times and a chained action starts at its predecessor's end,
  so the tick interval affects latency, never the speed of the game.
- **Continuous movement is a chain of short actions** (`orbit-step`), in the
  roguelike tradition of discrete actions; clients may animate in between
  because the action in progress is part of the state.
- The time unit has no name yet. Player intents and the player-facing game log
  of actions are not designed.

## 2026-11-12: a lead plans steps for local workers before any fallback
The first engine task (actions and game time) never reached the inference
box: the lead judged the whole task too subtle and wrote it alone, with no
worker and no independent review. "Too subtle for a small model" is therefore
no longer a reason to skip the plan. Every task is split into steps, every
step is assigned to a local worker, and a step leaves the plan only when the
box is down or the step has failed twice locally (steps touching auth or
secrets excepted). The report says who did each step.

## 2026-11-12: several actions per resource, picked by a `random` behaviour
A resource can list several action types; its behaviour decides which one
starts next. `random` picks among weighted choices and the pick is recorded
on the action as a `draw` (distribution, weights, the uniform `u`, the
result), following the rule that every random outcome shows what it was drawn
from. `wait` is the first action with effect `none`. The generator lives in
`world` until the randomness library exists; that library will own seeding
and the draw format, and `world`'s draw moves onto it then.

## 2026-11-12: an area log of every change, with causes
The state says what is true; players are promised what happened, in detail,
and chained actions in one tick already lose their intermediate draws. So
every write in an area gets an append-only log entry in the same transaction
(contract: `log.md`).
- **Playback over re-simulation.** Entries carry resulting state, so a client
  replays without game rules and old logs survive rule changes. Re-simulating
  from a seed would put the engine in the client.
- **`cause` and `root`** make one action and its consequences a tree (fire →
  line attack → projectile → damage → rubble). Only action start and end
  exist so far; the fields are there for the effects to come.
- **Snapshots plus retention** (hourly, 24 h) bound the size while the test
  area runs unwatched, and let a replay start anywhere. The lasting fix is
  not ticking idle areas (#37).
- **Versions stay one per resource per tick**; several entries may share one.
  Live clients stay cheap and the log holds the detail.
- The log lives in `world`'s schema for now, not in `libs/resource-model`.

## 2026-11-13: effects, and resources that come and go
One action can set off a chain (throw → line attack → projectile → damage →
break → rubble). An **effect** is the instant link in that chain and gets its
own log entry with a cause, so the log shows the chain as a tree. Actions keep
the time; effects never take any.
- The orbiter throws pebbles at the pillar so the whole chain runs with no
  player input. Debris decays so the area doesn't fill up.
- Resources are now created and destroyed while an area runs, which the
  notifications could not express: a change gains `children` and `deleted`,
  and a digest becomes authoritative for what exists. Destroyed changes carry
  no version, so nothing has to invent one.
- The log does not record child lists; `created` and `destroyed` are enough
  to rebuild them and a resource that lives for less than a tick needs no
  special case.
- Roll-out order: `resource-model`, `subscriptions` and the web client learn
  the new notification fields first; `world` starts creating resources last.
- A pillar at 0 hp just stays at 0. What destroying a damaged resource means
  is not decided.

## 2026-11-13: each layer produces one kind of evidence
The same suite was being run by the lead after every step, by the lead at the
end, by the tester, by the repo agent and then by CI. In five branches the
repo agent's rerun never disagreed with the lead's; what found bugs was
different work (a run against data shaped like the deployed database, a
tester exercising the service). So: the lead runs affected tests per step and
the full suite once on the final commit; the tester exercises the running
thing and doesn't rerun the suite; the repo agent reviews the diff and checks
the reported SHA; CI is the backstop before deploy. If a bad merge ever
reaches `main`, the next step is a pull request per branch with CI as the
gate before merge.

## 2026-11-13: the action contract has its own file
Game time, actions, behaviours and effects moved from `resources.md` to
`actions.md`, so a module that only handles resources and notifications
doesn't read the action rules. Earlier entries that cite `resources.md` for
those sections mean `actions.md`.

## 2026-11-13: the log is followed by a pushed position, not pushed entries
The log panel polled once a second. Pushing entries would mean either
`subscriptions` reading `world`'s tables or entries (full states) inside an
8KB notification. Instead each change batch carries `logSeq`, the log's new
end, and the client reads the entries it lacks from `world`. One number on a
message that is sent anyway; the client reads exactly when there is something
new, and the digest repairs a missed batch.

## Future ideas the design stays open for
Wall-clock-pinned turns (a turn with no action chosen is skipped), multiple
players in a shared world, interest management and fog of war, deltas in
patches, Redis Streams for fan-out, area leases across `world` instances.
