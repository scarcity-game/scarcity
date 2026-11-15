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
Contract: `actions.md`, `log.md` (kind `intent`), `resources.md`.
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

## Future ideas the design stays open for
Several players in one area (an advance stops when any controlled resource is
idle), interest management and fog of war, deltas in patches, Redis Streams
for fan-out, area leases across `world` instances, service JWTs from `auth`,
and services that control NPCs at different levels of intelligence through
the same intents API.
