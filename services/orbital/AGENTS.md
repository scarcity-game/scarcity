# AGENTS.md: services/orbital

Plays the entities whose `controller` is `{"kind":"service","principal":"orbital"}`
(the test area's orbiter): a **controller** (`docs/actions.md`, "Controllers",
"Intents", "Turns") that waits to be told its entity is up, reads it, decides,
and posts an intent, through the same API and WebSocket a player uses. Java
25, Spring Boot 3.5.7, package `com.pessimisticit.scarcity.orbital`. Depends
on `libs/world-model` (entity states, params records, draws) and
`libs/observability`. Lint applies with no baseline entries. Part of #103 (#114).

## Contract
- **Token**: `POST {AUTH_URL}/api/auth/service-token` with
  `X-Api-Key: <ORBITAL_API_KEY>` (root AGENTS.md, "Auth"). `ServiceToken`
  fetches it when first needed and again once two thirds of `expiresIn` have
  passed, or after a world 401 or a socket `4401`. No timer renews it:
  `World` sets `Authorization` per request from `ServiceToken.current()`, so
  REST calls switch to a new token 10 minutes into a 15-minute one
  (`PlayerTest`), and the socket gets a new one when subscriptions closes it
  `4401` at `exp`.
- **Socket** (`TurnSocket`, docs/websocket.md): `auth` with the token,
  `subscribe` to `orbital.area` on `ready`, and each `turn` frame for that
  area to `Turns`. Other frames are ignored: orbital reads what it needs from
  world when a turn comes.
- **Calls** world (`WORLD_URL`) with `Authorization: Bearer <token>`:
  `GET /api/world/resources/{id}` and `POST /api/world/intents`.
- **Serves** nothing on 8080 (it listens, as every HTTP service does).
  Actuator on **8081** (`metrics`, from `libs/observability`): `health`
  (probes enabled; the `subscription` indicator is in neither probe group)
  and `prometheus`.
- **Never logs the key or a token**: the key is a default header of auth's
  `RestClient` only, `OrbitalProperties.toString()` hides it, and
  `PlayerTest` and `ServiceTokenTest` check every path with a captured appender.

## A turn
subscriptions sends `turn` to orbital's socket when one of its entities is up,
and repeats it after every `changes` frame for the area and every digest
(about 30 s). Nothing in orbital polls.

1. `Turns.offer`: ignored if that entity and version is being played, or
   already settled (`ok`, `conflict`, `stale`). Otherwise it plays
   `orbital.think` (1 s) later on the one thread `orbital-turns`, so turns
   never overlap. A turn that failed (`unreachable`, `invalid`, …) plays
   again on the next repeat; that repeat is the back-off.
2. `Player.turn` (one `orbital.turn` observation, so its calls carry one
   `traceparent` and world's advance joins that trace): `GET` the entity. If
   its version isn't the turn's, or it has an `action`, the turn ends
   (`stale`): a newer turn will come.
3. `Chooser.pick(actions)`: a categorical draw (`Draws.categorical`, one
   seeded `SplittableRandom`) over the **open** choices: among the entity's
   `actions`, weight above 0 and not skipped.
4. The choice plans its params with world-model's records (`Choice.plan`):
   - `step` (`StepChoice`): to the point `arc / radius` further round the
     circle from the entity's **actual** angle around `center`, so a short
     step doesn't drift the radius.
   - `wait` (`WaitChoice`): `duration`.
   - `throw` (`ThrowChoice`): `GET` the target, `at` its position, with
     `spread`, `recovery` and the projectile (read at startup; a bad one
     fails startup). A 404 target is `Choice.Unavailable`.
5. `POST` the intent with `ifVersion` = the turn's version, and
   `reported: {"draw": {"dist":"categorical","weights","u","value"}, "choices": [names]}`:
   `value` indexes `choices` (and `weights`), which world logs verbatim.

New choice = one `@Component` implementing `Choice`, with a weight in
`orbital.weights`; `Chooser` (a `@Service`) gets every `Choice` bean, and
nothing branches on its name.

| outcome (`orbital.intents{outcome}`) | when | repeat of the same turn |
|---|---|---|
| `ok` | 200 | ignored |
| `stale` | the entity is at another version, or busy; nothing posted | ignored |
| `conflict` | 409: changed, or no longer up (`STALE`, `BUSY`, `NOT_UP`) | ignored |
| `invalid` | 422: the choice sits out 1 min; WARN once per (choice, reason) | played |
| `unavailable` | no open choice among the entity's actions (WARN), or `Choice.Unavailable` (sits out 1 min, WARN once) | played |
| `denied` | world 401 or 403, or auth refused the key (ERROR) | played |
| `not_found` | 404 for the entity (ERROR) | played |
| `unreachable` | auth or world didn't answer (WARN) | played |
| `error` | anything else: 5xx, an unreadable state (ERROR) | played |

## The socket
`TurnSocket` (lifecycle, reconnect, silence; one `Connection` per attempt),
`SocketListener` (the JDK callbacks, joined into JSON frames), `Frames` (what
orbital sends and reads), `BackOff`. Reconnects after a close or a failure, waiting `orbital.reconnect` (1 s),
doubling to `orbital.reconnect-max` (30 s), back to 1 s once subscribed again.
A `4401` (the token expired: subscriptions closes at `exp`) drops the token
first. A socket that hears nothing, not even subscriptions' 30 s ping, for
`orbital.silence` (90 s) is dropped and reconnected. Health `subscription`
is UP while subscribed, with `subscribedSince` and `lastAcceptedIntent`.

## Configuration (`application.yml`, prefix `orbital`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `AUTH_URL` | `http://localhost:8080` | k8s `http://auth:8080` |
| `ORBITAL_API_KEY` | | **required**, startup fails without it; k8s: from secret `service-api-keys`, key `orbital` |
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `SUBSCRIPTIONS_URL` | `ws://localhost:8080/api/subscriptions/ws` | k8s `ws://subscriptions:8080/api/subscriptions/ws` |
| `ORBITAL_AREA` | the test area `area:…0002` | the area subscribed to |
| `ORBITAL_THINK` | `1s` | between a turn frame and acting on it |
| `orbital.reconnect`, `reconnect-max`, `silence` | 1 s, 30 s, 90 s | the socket |
| `ORBITAL_SEED` | random (logged at startup) | the draws' seed |
| `orbital.weights` | step 2.0, wait 1.0, throw 1.0 | an unknown name fails startup; 0 = never |
| `orbital.step` | center (5, 4), radius 3.0, arc 0.5 | |
| `orbital.wait.duration` | 1.0 | |
| `orbital.throw` | target the pillar `…0003`, spread 0.2, recovery 2.0, projectile as JSON | the pebble of the old seed |
| `SPRING_PROFILES_ACTIVE`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES` | | as world |

The orbit, target and projectile are orbital's, not game state. Bad values
fail startup naming their key (`OrbitalPropertiesTest`).
`spring.http.client.connect-timeout` 2 s, `read-timeout` 10 s.

## Tests
`mvn -B -f ../../pom.xml -pl services/orbital -am verify` (needs Docker).
`StubWorld` is world's API and auth's token exchange on the JDK `HttpServer`;
`StubSubscriptions` is subscriptions' WebSocket, a JSR-356 endpoint on its
own embedded Tomcat.
- `ChooserTest`: 10,000 seeded draws follow 2:1:1 (within 0.02); only the
  entity's actions are open; the draw's `value` is `Draws.pick` of its `u`; a
  skip lasts exactly a minute; zero and unknown weights.
- `StepChoiceTest`: the next point, off-orbit positions, no drift over 10,000 steps.
- `OrbitalPropertiesTest`: the defaults bind; each bad value fails startup.
- `ServiceTokenTest`: kept for 2/3 of its lifetime, renewed, a wrong key.
- `PlayerTest`: the intent's shape (`ifVersion`, params, `reported`), the
  token on every request, the throw's `at`, every error path, a 401 fetching
  a new token, the key and token in no log line.
- `TurnsTest`: once per turn however often repeated; a failed turn plays on
  its repeat; the thinking time.
- `BackOffTest`, `FramesTest`.
- `TurnSocketTest`: auth then subscribe; turn frames for the area only;
  reconnect after a close; a `4401` fetching a new token; silence; stop.
- `OrbitalAppTest`: the wired service against the stubs: a repeated turn
  frame becomes one intent after the thinking time, all of it in one trace;
  health `subscription`; the Prometheus counter.
- `PlaysRealWorldTest`: auth's, world's and subscriptions' jars, each in its
  own JVM (`ServiceProcess`), on one Testcontainers Postgres, orbital
  in-process: within 20 s at least three intents `by: {"kind":"service",
  "principal":"orbital"}`, and no 409.

**How the build finds the jars.** The `services-it` profile (active unless
`-DskipTests`) declares `scarcity-auth`, `scarcity-world` and
`scarcity-subscriptions` as test dependencies of type `pom` with every
transitive excluded: they only order the reactor, so `-am` packages them
before these tests and nothing of them is on the classpath. Their directory
is the surefire property `services.dir`. The Dockerfile passes `-DskipTests`,
so the image builds orbital and its libs only. That is why `modules.yaml`
lists those services and their libs in orbital's `paths`.

## Deployment (#115)
`modules.yaml` has `deploy: false` until #115: the deploy job waits on the
rollout of every `deploy: true` module, and `k8s-resources/orbital` doesn't
exist yet. #115 needs:
- the secret `service-api-keys` with data key `orbital: <key>`, mounted into
  auth at `AUTH_SERVICE_KEYS_DIR`, and given to orbital as env
  `ORBITAL_API_KEY` (`secretKeyRef`, key `orbital`);
- `k8s-resources/orbital/` (Deployment `orbital`, **1 replica**: two would
  both be told every turn, and one would get 409s; a Service is only needed
  for scraping), env `AUTH_URL`, `WORLD_URL`, `SUBSCRIPTIONS_URL`,
  `SPRING_PROFILES_ACTIVE=k8s`, the OTLP variables as world; ports 8080
  `http`, 8081 `metrics`; liveness and readiness on
  `/actuator/health/liveness|readiness` at 8081; requests and limits (a
  small JVM, as subscriptions);
- `deploy: true` here, and the image pull secret as the other services.

## Open follow-ups
- One instance only: a second replica would be told the same turns. If it
  ever needs two, it needs leader election, not more intents.
