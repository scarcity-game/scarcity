# AGENTS.md: libs/service-controller

What every service that plays entities shares (#170): a **controller**
(`docs/actions.md`, "Controllers", "Intents", "Turns") that waits to be told
its entity is the active entity, reads it, draws one of its choices, and
posts an intent, through the same API and WebSocket a player uses. Package
`com.pessimisticit.scarcity.controller`, Spring auto-configuration
(`ControllerAutoConfiguration`). Depends on `libs/world-model`. Lint applies
with no baseline entries. Extracted from `services/orbital`; `microbe` and
`diver` use it too.

## Two ways to be told a turn: `controller.mode`
- **`socket`** (the default; `orbital`, `diver`): the service gets a token with its
  API key, keeps a WebSocket to subscriptions, and acts through world's API
  (`SocketConfiguration`: `ServiceToken`, `World`, `Player`, `Turns`, `TurnSocket`,
  `SubscriptionHealth`). Described below as "a turn".
- **`endpoint`** (an **intelligence service**, `microbe`; docs/actions.md): world POSTs
  each turn to the service's `POST /turn` and takes the answer
  (`EndpointConfiguration`: `TurnEndpoint`, `WorldCaller`). No socket, no `TurnFeed`,
  no `World` client, no `ServiceToken`, no API key; the service calls nobody.
  See "The turn endpoint" below.

Why a property and two conditional `@Configuration`s, not two auto-configurations or
one properties record with conditional validation: a service is exactly one of the
two, a service's own `application.yml` says which in one line, and each mode's
required settings (socket: key and URLs; endpoint: `jwk-set-uri`) are validated by
Bean Validation only when that mode is on, so an endpoint service needs no key and
a socket service needs no JWKS (`ControllerModeTest`). An unknown mode fails
startup. What both modes share is `CommonProperties` (`mode`, `principal`, `seed`,
`weights`, `fallback-wait`), `ControllerConfig` (the clock, the seeded draws),
`Chooser`, `Planner` and `OutcomeCounters`. `ControllerProperties` (socket mode only)
holds the socket's settings. The JWT verification
(`spring-security-oauth2-jose`) is an **optional** dependency of this library, so
`orbital` and `diver` don't get Spring Security; an endpoint service adds it itself.

## Using it
- Depend on `service-controller`, and on its `test-jar` (test scope) for
  `StubWorld` (auth's token exchange and world's API on the JDK
  `HttpServer`, `children` included), `StubSubscriptions` (subscriptions' WebSocket on its own
  embedded Tomcat), `TestClock`, `CapturedLogs` and `ServiceProcess` (a
  service's jar in its own JVM, for tests against the real services).
- Set the `controller` block (`CommonProperties`, and `ControllerProperties` in
  socket mode, `@Validated`): `mode` (`socket` | `endpoint`),
  `principal` (also the prefix of metrics, the observation and threads),
  `auth-url`, `api-key` (required, never logged), `world-url`,
  `subscriptions-url`, `area` (blank: the **turn feed**, below), `think`, `reconnect`, `reconnect-max`,
  `silence`, `fallback-wait` (game seconds of the fallback wait, below; > 0, default 1.0), `seed` (blank: random, logged), `weights` (choice name to
  weight; an unknown name fails startup, 0 = never).
- Define each choice as a `@Component` implementing `Choice`; `Chooser` gets
  every `Choice` bean. Nothing branches on a choice's name.
  `plan(situation)` returns a `Choice.Plan`: the action, or `Plan.none(reason)`
  when it isn't feasible now, and the draws it made to plan it (by what each
  was for, in the order made), which go into the intent's `reported`.
  `Choice.Situation` is the turn, the entity as just read and an `AreaView` of every
  entity in its area and its cells (read once per turn by `Player`, or built from
  the request in endpoint mode), and the faction relations it plays by (the
  request's `factions` in endpoint mode, `Factions.standard()` over the socket). It has **no `World`**: in endpoint mode there is
  none, and everything a choice may use is in the view (`diver`: step while the
  stairs are out of reach and there is a way, traverse once they are in reach,
  wait otherwise). A movement choice uses
  `ClearStep`: `clear(view, self, to)` is whether a step meets nothing before
  it ends (world-model's `Contacts.first`, everything else standing still), and
  `anyHeading(situation, rng, distance)` draws up to `ClearStep.TRIES` (10)
  headings uniformly from [0, 2π), recorded as `heading.1`, `heading.2`, …, and
  takes the first clear one, or is `none` (#316). `World` (socket mode) reads an entity, an
  area (`area(id)`) or the entities in an area (`entities(id)`, the first 200
  children), and posts intents. The service's own
  settings (how its choices plan) are its own `@ConfigurationProperties`.
- The beans: `ControllerConfig` (the `Clock`, the seeded `RandomGenerator`),
  `Chooser`, and per mode, socket: `ServiceToken`, `World`, `Player`, `Turns`,
  `TurnSocket`, `SubscriptionHealth` (health `subscription`); endpoint:
  `TurnEndpoint`, `WorldCaller`, `Planner`, `OutcomeCounters`.
- The test-jar also has `TestJwks` (an RSA key, a JDK `HttpServer` serving its JWKS,
  and `worldToken()` / `token(sub, kind, expires)`) to test an endpoint service
  with real tokens. `ServiceProcess` is unchanged.

## Adding an intelligence service
(#375, #376, #377.) A service world calls for the turns of the entities whose
controller names it, replicated behind a k8s Service.
1. New module `services/<name>` (as `services/microbe`: pom, Dockerfile, entry in
   `modules.yaml` and `config/docker/maven-deps.Dockerfile`, k8s directory).
   Depend on `service-controller`, `libs/observability`, `spring-boot-starter-validation`
   and `spring-security-oauth2-jose`; test scope: `service-controller`'s `test-jar`.
2. Write the choices as `@Component`s implementing `Choice` (above). They see the
   entity and the area's `AreaView` only: no calls out, no state kept between
   turns (replicas share none; each turn is planned from the request alone).
3. `application.yml`: `controller.mode: endpoint`, `controller.principal`,
   `controller.jwk-set-uri` (k8s: `http://auth:8080/api/auth/.well-known/jwks.json`),
   `controller.weights`, optionally `controller.seed` (leave it unset in the cluster:
   replicas should not repeat each other's draws) and `controller.fallback-wait`.
   No API key, no URLs of world or subscriptions.
4. k8s: a Deployment with `replicas` of 2 or more, `RollingUpdate` with
   `maxUnavailable: 0`, a readiness probe, and a Service `<name>:8080`.
5. world must be told the principal's base URL; that registry is world's, not this
   library's (docs/actions.md, "Intelligence services").
6. Test: a `@SpringBootTest` with a `TestJwks` (see `MicrobeAppTest`): `POST /turn`
   with `worldToken()` and a `TurnRequest` gives a valid intent body; other tokens
   are refused.


## Contract
- **Token**: `POST {AUTH_URL}/api/auth/service-token` with
  `X-Api-Key: <controller.api-key>` (root AGENTS.md, "Auth"). `ServiceToken`
  fetches it when first needed and again once two thirds of `expiresIn` have
  passed, or after a world 401 or a socket `4401`. No timer renews it:
  `World` sets `Authorization` per request from `ServiceToken.current()`, so
  REST calls switch to a new token 10 minutes into a 15-minute one
  (`PlayerTest`), and the socket is rotated at the same moment (see "The socket"),
  so subscriptions never closes it `4401` at `exp`; a `4401` still drops the token.
- **Socket** (`TurnSocket`, docs/websocket.md): `auth` with the token,
  `subscribe` to `controller.area` on `ready`, and each `turn` frame for that
  area to `Turns`. Other frames are ignored: the controller reads what it needs from
  world when a turn comes.
- **Calls** world (`WORLD_URL`) with `Authorization: Bearer <token>`:
  `GET /api/world/resources/{id}` and `POST /api/world/intents`.
- **Serves** nothing on 8080 in socket mode (it listens, as every HTTP service does);
  `POST /turn` in endpoint mode.
  Actuator on **8081** (`metrics`, from `libs/observability`): `health`
  (probes enabled; the `subscription` indicator is in neither probe group)
  and `prometheus`.
- **Never logs the key or a token**: the key is a default header of auth's
  `RestClient` only, `ControllerProperties.toString()` hides it, and
  `ServiceTokenTest` (and orbital's `PlayerTest`) check every path with a captured appender.

## The turn endpoint
`POST /turn` (`TurnEndpoint`, endpoint mode), body a `TurnRequest` of `libs/world-model`
(area id, entity id, `ifVersion`, the entity, the area's other entities and cells), answer
`200` with a `TurnAnswer` (`actionSpec`, `reported`; world adds `entity` and `ifVersion`
to make the intent).
- **Auth** (`WorldCaller`): `Authorization: Bearer <jwt>`, verified against
  `controller.jwk-set-uri` (RS256, `iss=scarcity-auth`, not expired), and the token must
  have `kind=service` and `sub=world`. No or invalid token: **401**. A valid token of
  anyone else (another service, a user, a `sub=world` without `kind=service`): **403**.
  Keys unreachable: **503**. A body that doesn't parse (strict `WorldJson`) or isn't a
  consistent area: **400**.
- **The view**: `TurnEndpoint.situation` builds the `AreaView` from the request. An entity
  whose `cell` is not among the request's cells is dropped (`AreaView.of` would refuse
  the wall, and one stray wall must not stop a turn); the entity itself is added.
- **Planning** is `Planner.decide(situation)`, pure, and the same code `Player` uses:
  eligible choices plan, one feasible is drawn by weight (`reported` as under "A turn");
  none feasible gives the fallback `wait` of `controller.fallback-wait` if `wait` is among
  the entity's actions. If it is not, the answer is **422** (logged at WARN): the
  service has nothing to say, and world applies its own policy.
- **Metrics and span**: observation `<principal>.turn` (tag `outcome`) and counters
  `<principal>.intents{outcome}`: `ok`, `fallback`, `unavailable` (the 422), `denied`
  (401, 403, 503), `invalid` (400), `error` (500).
- Nothing is kept between turns, so any replica can answer any turn.

## A turn
(Socket mode.) subscriptions sends `turn` to the controller's socket when one of its entities is the active entity,
and repeats it after every `changes` or `reload` frame for the area and every
checksum (about 30 s). Nothing polls.

1. `Turns.offer`: ignored if that entity and version is being played, or
   already settled (`ok`, `conflict`, `stale`). Otherwise it plays
   `controller.think` later (or what an optional `TurnDelay` bean returns for
   the frame; diver pauses once per area) on the one thread
   `<principal>-turns`, so turns
   never overlap. A turn that failed (`unreachable`, `invalid`, …) plays
   again on the next repeat; that repeat is the back-off.
2. `Player.turn` (one `<principal>.turn` observation, so its calls carry one
   `traceparent` and world's advance joins that trace): `GET` the entity. If
   it has an `action`, or its version is behind the turn's, the turn ends
   (`stale`): a newer turn will come. If its version is *ahead* of the turn's
   (subscriptions' version map is stale, and every repeat of the turn carries
   the old version), `GET` the area: when `activeEntity.entity` is this entity
   it plays at its own version (INFO log "playing at"), otherwise `stale`.
   World decides, so a race is a 409 (`conflict`). Then `GET` the area's
   entities, once.
3. **Plan first.** Every **eligible** choice (`Chooser.eligible`: among the
   entity's `actions`, weight above 0) plans (`Choice.plan(situation)`), with
   world-model's records, and says whether it is feasible now.
4. `Chooser.pick(actions, feasible)`: a categorical draw (`Draws.categorical`,
   one seeded `SplittableRandom`) over the **feasible** choices only, by weight.
5. `POST` the drawn plan with `ifVersion` = the entity's version as just read, and
   `reported: {"draw": {"dist":"categorical","weights","u","value"}, "choices": [names],
   "plan": {its draws}, "considered": {name: {"infeasible": reason, "plan": {draws}}}}`:
   `value` indexes `choices` (and `weights`); `plan` is the drawn choice's own
   draws, and `considered` what the other choices planned (an infeasible one
   with why, any with draws with them; neither key when empty). world logs it
   verbatim.

**The fallback wait.** Turns are strictly ordered, so an active entity whose
controller posts nothing stalls every entity in the area. When no choice yields
an intent (none feasible, or world refuses the drawn one with a 422), `Player`
posts `wait` for `controller.fallback-wait` instead, in the same turn, if `wait`
is among the entity's `actions`: outcome `fallback`, `reported: {"fallback":
{"reason", "duration"}, "considered": …}` and no categorical `draw`. When the
entity is up again it plans and draws afresh: **nothing is benched**, for it or
for any other entity the service plays, and nothing depends on wall-clock time
(#316). A 422 on a `wait` choice, or on the fallback itself, is `invalid` with
no retry; without `wait` among the actions the outcomes are as before.

New choice = one `@Component` implementing `Choice`, with a weight in
`controller.weights`; `Chooser` (a `@Service`) gets every `Choice` bean, and
nothing branches on its name.

| outcome (`<principal>.intents{outcome}`) | when | repeat of the same turn |
|---|---|---|
| `ok` | 200 | ignored |
| `stale` | the entity is behind the turn, or ahead of it and not the area's active entity, or busy; nothing posted | ignored |
| `conflict` | 409: changed, or no longer active (`STALE`, `BUSY`, `NOT_ACTIVE`) | ignored |
| `fallback` | no choice gave an intent (none feasible, or a 422 on a choice other than `wait`), so a `wait` of `controller.fallback-wait` was accepted instead | ignored |
| `invalid` | 422 on `wait` itself or on the fallback; WARN once per (choice, reason), as for any 422 | played |
| `unavailable` | as `fallback`, but `wait` is not among the entity's actions (WARN) | played |
| `denied` | world 401 or 403, or auth refused the key (ERROR) | played |
| `not_found` | 404 for the entity (ERROR) | played |
| `unreachable` | auth or world didn't answer (WARN) | played |
| `error` | anything else: 5xx, an unreadable state (ERROR) | played |

## The socket
`TurnSocket` (lifecycle, reconnect, silence; one `Connection` per attempt),
`SocketListener` (the JDK callbacks, joined into JSON frames), `Frames` (what
a controller sends and reads), `BackOff`. A socket for one area connects to
`subscriptions-url?area=<area>` (unencoded: the router's hash key,
`docs/websocket.md` "Endpoint"); the turn feed's socket names no area. Reconnects after a close or a
failure (a drain's `1012` included), waiting `controller.reconnect`, doubling to
`controller.reconnect-max`, back to the first once subscribed again; each wait is jittered to a random
point in its upper half (`docs/websocket.md`, "Reconnecting and resume").
An `error` frame before `subscribed` (world was down when subscriptions loaded
the area; the socket stays open) sends `subscribe` again on the same socket
after the same back-off (shared with reconnects, reset by `subscribed`); an
`error` after `subscribed` does the same on the turn feed (subscriptions
acknowledges, then fails to list the areas when world is down; the feed would
stay empty) and is only logged for a single area; the retry is guarded so
only one is pending, and subscriptions joins an area once. A `4401` (the token expired: subscriptions closes at `exp`) drops the token
first. **Token rotation** (#436): an open socket keeps the token it sent in `auth`, and
there is no in-band re-auth (a second `auth` is an `error`), so once the subscribed
socket's token is due for renewal (`ServiceToken.renewAt()`, 2/3 of its life) `TurnSocket`
opens a second socket with a fresh token (`ServiceToken.fresh`), subscribes it, and only then
closes the first (normal closure): no gap, and a turn heard on both is offered twice to
`Turns`, which ignores the repeat. If the second socket fails to open, the first stays and
the attempt repeats after the back-off; if the first dies meanwhile, the second takes over. A socket that hears nothing, not even subscriptions' 30 s ping, for
`controller.silence` is dropped and reconnected. Health `subscription`
is UP while subscribed, with `subscribedSince` and `lastAcceptedIntent`.

## The turn feed
With no `controller.area`, `TurnSocket` sends `{"type":"subscribe","turns":true}`
on `ready` instead of an area (docs/websocket.md): subscriptions joins it to
every area its principal is up in, now and later. It counts as subscribed on
`{"type":"subscribed","turns":true}` (the `subscribed` frames of the areas it
is joined to don't count), and passes every `turn` frame, of any area, to
`Turns`. For a service whose entities are in levels made lazily (`microbe`).

## Tests
`mvn -B -f ../../pom.xml -pl libs/service-controller -am verify` (no Docker).
- `ChooserTest`: 10,000 seeded draws follow 2:1:1 (within 0.02); only the
  entity's actions, and only feasible choices, are drawn from; the draw's `value` is `Draws.pick` of its `u`;
  a choice infeasible once is a candidate again; zero and unknown weights.
- `ClearStepTest`: a step is clear only if it meets nothing before it ends; from
  against a wall, away but not in; in a corner most turns find a clear heading
  and every heading tried is recorded; boxed in, none after ten.
- `PlayerTest`: a 422 is a wait for that entity alone and the other entity's next
  turn still draws among step and wait; with no clear step only wait is drawn,
  reporting the ten headings tried; an unchosen step's draws are reported too.
- `TurnEndpointTest` (endpoint mode, `TestJwks`): a turn gives an intent; a wall whose
  cell is missing is dropped; 403 for another service, a user and `sub=world` without
  `kind`; 401 for no, garbage, forged and expired tokens; 400 for a bad body; 422 when
  the entity can't wait; no socket, `World`, `ServiceToken` or `Turns` bean.
- `ControllerModeTest`: endpoint mode needs only its JWKS; socket mode (the default)
  needs its key and URLs; an unknown mode fails.
- `ServiceTokenTest`: kept for 2/3 of its lifetime, renewed, a wrong key.
- `TurnsTest`: once per turn however often repeated; a `fallback` settles the turn; a failed turn plays on
  its repeat; the thinking time.
- `BackOffTest` (doubling, reset, jitter), `FramesTest` (the feed's frames too).
- `TurnSocketTest`: auth then subscribe, the area in the URL; with no area (and none in the URL) the turn feed and turns
  of any area; turn frames for the area only;
  reconnect after a close and after a `1012`; an `error` before `subscribed` subscribes again
  after the back-off, on the turn feed so does an `error` after it (then turns arrive), for one area it doesn't; a `4401` fetching a new token; a token due for renewal opens a second socket with the new token and closes the first normally (no `4401`), turns arriving on the second; silence; stop.
- `Player` is tested with real choices in orbital's `PlayerTest`, the fallback wait included.

## Open follow-ups
- Socket mode: one instance per principal (a second replica would be told the same
  turns). Endpoint mode has no such limit.
