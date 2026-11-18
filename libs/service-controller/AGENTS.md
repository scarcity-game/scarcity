# AGENTS.md: libs/service-controller

What every service that plays entities shares (#170): a **controller**
(`docs/actions.md`, "Controllers", "Intents", "Turns") that waits to be told
its entity is the active entity, reads it, draws one of its choices, and
posts an intent, through the same API and WebSocket a player uses. Package
`com.pessimisticit.scarcity.controller`, Spring auto-configuration
(`ControllerAutoConfiguration`). Depends on `libs/world-model`. Lint applies
with no baseline entries. Extracted from `services/orbital`; `microbe` and
`diver` use it too.

## Using it
- Depend on `service-controller`, and on its `test-jar` (test scope) for
  `StubWorld` (auth's token exchange and world's API on the JDK
  `HttpServer`, `children` included), `StubSubscriptions` (subscriptions' WebSocket on its own
  embedded Tomcat), `TestClock`, `CapturedLogs` and `ServiceProcess` (a
  service's jar in its own JVM, for tests against the real services).
- Set the `controller` block (`ControllerProperties`, `@Validated`):
  `principal` (also the prefix of metrics, the observation and threads),
  `auth-url`, `api-key` (required, never logged), `world-url`,
  `subscriptions-url`, `area` (blank: the **turn feed**, below), `think`, `reconnect`, `reconnect-max`,
  `silence`, `seed` (blank: random, logged), `weights` (choice name to
  weight; an unknown name fails startup, 0 = never).
- Define each choice as a `@Component` implementing `Choice`; `Chooser` gets
  every `Choice` bean. Nothing branches on a choice's name.
  `plan(turn, self, world)` returns a `Choice.Plan`: the action, and the
  draws it made to plan it (by what each was for), which go into the
  intent's `reported.plan`. A choice that only makes sense in some states
  overrides `open(turn, self, world)` (default: always open); `Chooser` draws
  only among open choices, so a choice that is closed now is not skipped for
  a minute (`diver`: step while the stairs are out of reach, traverse once
  they are in reach). `World` reads an entity, an area (`area(id)`, for its
  size) or the entities in an area (`entities(id)`, the first 200 children), and posts intents. The service's own
  settings (how its choices plan) are its own `@ConfigurationProperties`.
- The beans: `ControllerConfig` (the `Clock`, the seeded `RandomGenerator`),
  `ServiceToken`, `World`, `Chooser`, `Player`, `Turns`, `TurnSocket`,
  `SubscriptionHealth` (health `subscription`).

## Contract
- **Token**: `POST {AUTH_URL}/api/auth/service-token` with
  `X-Api-Key: <controller.api-key>` (root AGENTS.md, "Auth"). `ServiceToken`
  fetches it when first needed and again once two thirds of `expiresIn` have
  passed, or after a world 401 or a socket `4401`. No timer renews it:
  `World` sets `Authorization` per request from `ServiceToken.current()`, so
  REST calls switch to a new token 10 minutes into a 15-minute one
  (`PlayerTest`), and the socket gets a new one when subscriptions closes it
  `4401` at `exp`.
- **Socket** (`TurnSocket`, docs/websocket.md): `auth` with the token,
  `subscribe` to `controller.area` on `ready`, and each `turn` frame for that
  area to `Turns`. Other frames are ignored: the controller reads what it needs from
  world when a turn comes.
- **Calls** world (`WORLD_URL`) with `Authorization: Bearer <token>`:
  `GET /api/world/resources/{id}` and `POST /api/world/intents`.
- **Serves** nothing on 8080 (it listens, as every HTTP service does).
  Actuator on **8081** (`metrics`, from `libs/observability`): `health`
  (probes enabled; the `subscription` indicator is in neither probe group)
  and `prometheus`.
- **Never logs the key or a token**: the key is a default header of auth's
  `RestClient` only, `ControllerProperties.toString()` hides it, and
  `ServiceTokenTest` (and orbital's `PlayerTest`) check every path with a captured appender.

## A turn
subscriptions sends `turn` to the controller's socket when one of its entities is the active entity,
and repeats it after every `changes` frame for the area and every digest
(about 30 s). Nothing polls.

1. `Turns.offer`: ignored if that entity and version is being played, or
   already settled (`ok`, `conflict`, `stale`). Otherwise it plays
   `controller.think` later on the one thread `<principal>-turns`, so turns
   never overlap. A turn that failed (`unreachable`, `invalid`, …) plays
   again on the next repeat; that repeat is the back-off.
2. `Player.turn` (one `<principal>.turn` observation, so its calls carry one
   `traceparent` and world's advance joins that trace): `GET` the entity. If
   its version isn't the turn's, or it has an `action`, the turn ends
   (`stale`): a newer turn will come.
3. `Chooser.pick(actions, open)`: a categorical draw (`Draws.categorical`, one
   seeded `SplittableRandom`) over the **open** choices: among the entity's
   `actions`, weight above 0, not skipped, and `Choice.open` for this turn.
4. The choice plans its params (`Choice.plan(self, world)`), with world-model's
   records; one that can't be made now throws `Choice.Unavailable`.
5. `POST` the intent with `ifVersion` = the turn's version, and
   `reported: {"draw": {"dist":"categorical","weights","u","value"}, "choices": [names]}`:
   `value` indexes `choices` (and `weights`), which world logs verbatim.

New choice = one `@Component` implementing `Choice`, with a weight in
`controller.weights`; `Chooser` (a `@Service`) gets every `Choice` bean, and
nothing branches on its name.

| outcome (`<principal>.intents{outcome}`) | when | repeat of the same turn |
|---|---|---|
| `ok` | 200 | ignored |
| `stale` | the entity is at another version, or busy; nothing posted | ignored |
| `conflict` | 409: changed, or no longer active (`STALE`, `BUSY`, `NOT_ACTIVE`) | ignored |
| `invalid` | 422: the choice sits out 1 min; WARN once per (choice, reason) | played |
| `unavailable` | no open choice among the entity's actions (WARN), or `Choice.Unavailable` (sits out 1 min, WARN once) | played |
| `denied` | world 401 or 403, or auth refused the key (ERROR) | played |
| `not_found` | 404 for the entity (ERROR) | played |
| `unreachable` | auth or world didn't answer (WARN) | played |
| `error` | anything else: 5xx, an unreadable state (ERROR) | played |

## The socket
`TurnSocket` (lifecycle, reconnect, silence; one `Connection` per attempt),
`SocketListener` (the JDK callbacks, joined into JSON frames), `Frames` (what
a controller sends and reads), `BackOff`. Reconnects after a close or a failure, waiting `controller.reconnect`,
doubling to `controller.reconnect-max`, back to 1 s once subscribed again.
An `error` frame before `subscribed` (world was down when subscriptions loaded
the area; the socket stays open) sends `subscribe` again on the same socket
after the same back-off (shared with reconnects, reset by `subscribed`); an
`error` after `subscribed` does the same on the turn feed (subscriptions
acknowledges, then fails to list the areas when world is down; the feed would
stay empty) and is only logged for a single area; the retry is guarded so
only one is pending, and subscriptions joins an area once. A `4401` (the token expired: subscriptions closes at `exp`) drops the token
first. A socket that hears nothing, not even subscriptions' 30 s ping, for
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
  entity's actions, and only choices open now, are drawn from; the draw's `value` is `Draws.pick` of its `u`; a
  skip lasts exactly a minute; zero and unknown weights.
- `ServiceTokenTest`: kept for 2/3 of its lifetime, renewed, a wrong key.
- `TurnsTest`: once per turn however often repeated; a failed turn plays on
  its repeat; the thinking time.
- `BackOffTest`, `FramesTest` (the feed's frames too).
- `TurnSocketTest`: auth then subscribe; with no area the turn feed and turns
  of any area; turn frames for the area only;
  reconnect after a close; an `error` before `subscribed` subscribes again
  after the back-off, on the turn feed so does an `error` after it (then turns arrive), for one area it doesn't; a `4401` fetching a new token; silence; stop.
- `Player` is tested with real choices in orbital's `PlayerTest`.

## Open follow-ups
- One instance per principal: a second replica would be told the same turns.
