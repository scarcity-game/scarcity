# AGENTS.md: services/orbital

Plays the test area's orbiter: a **controller** (`docs/actions.md`,
"Controllers", "Intents") that reads the game state, decides, and posts an
intent to world, through the same API a player uses. Java 25, Spring Boot
3.5.7, package `com.pessimisticit.scarcity.orbital`. Depends on
`libs/world-model` only (entity states, params records, draws). Lint applies
with no baseline entries. Part of #103 (#114).

## Contract
- **Calls** world (`WORLD_URL`) with `X-Api-Key: <ORBITAL_API_KEY>`, as the
  service principal `orbital`: `GET /api/world/resources/{id}` and
  `POST /api/world/intents`. Never with a JWT.
- **Serves** nothing on 8080 (it listens, as every HTTP service does).
  Actuator on **8081** (`metrics`): `health` (probes enabled; the `world`
  indicator is in neither probe group) and `prometheus`.
- **Never logs the key**: it is a default header of the `RestClient`,
  `OrbitalProperties.toString()` hides it, and `PlayerTest` checks every
  error path with a captured appender.

## The loop (`OrbitalLoop`, thread `orbital-loop`)
One turn (`Player.turn`), then the wait `Pacing` gives for its `Outcome`,
counted from the response, then the next turn. A turn is one `orbital.turn`
observation, so its calls carry one `traceparent` and world's advance and
batch join that trace.

1. `GET` the entity. If it has an `action`, the turn ends (`busy`).
2. `Chooser.pick()`: a categorical draw (`Draws.categorical`, one seeded
   `SplittableRandom`) over the **open** choices: weight above 0 and not
   skipped.
3. The choice plans its params with world-model's records (`Choice.plan`):
   - `step` (`StepChoice`): to the point `arc / radius` further round the
     circle from the entity's **actual** angle around `center`, so a short
     step doesn't drift the radius.
   - `wait` (`WaitChoice`): `duration`.
   - `throw` (`ThrowChoice`): `GET` the target, `at` its position, with
     `spread`, `recovery` and the projectile (read at startup; a bad one
     fails startup). A 404 target is `Choice.Unavailable`.
4. `POST` the intent with `ifVersion` = the version read, and
   `reported: {"draw": {"dist":"categorical","weights","u","value"}, "choices": [names]}`:
   `value` indexes `choices` (and `weights`), which world logs verbatim.

New choice = one `Choice` class, listed in `OrbitalConfig.player` with a
weight in `orbital.weights`; nothing else branches on its name.

| outcome (`orbital.intents{outcome}`) | when | next turn after |
|---|---|---|
| `ok` | 200 | interval |
| `busy` | the entity has an action; nothing posted | interval |
| `stale` | 409 (re-read next turn) | interval |
| `invalid` | 422: the choice sits out 1 min; WARN once per (choice, reason) | interval |
| `unavailable` | no open choice, or `Choice.Unavailable` (sits out 1 min, WARN once) | interval |
| `denied` | 401 or 403 (ERROR) | 30 s |
| `not_found` | 404 for the entity (ERROR) | 30 s |
| `unreachable` | no answer (WARN) | doubling from the interval, at most 30 s |
| `error` | anything else: 5xx, an unreadable state (ERROR) | as `unreachable` |

The loop never ends on an error. Health `world` is UP while the last `ok` is
younger than `orbital.health-window` (10 s), DOWN before the first one.

## Configuration (`application.yml`, prefix `orbital`)
| env var / key | default | meaning |
|---|---|---|
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `ORBITAL_API_KEY` | | **required**, startup fails without it; k8s: from secret `world-api-keys`, key `orbital` |
| `ORBITAL_ENTITY` | the orbiter `entity:…0004` | the entity played; its `controller` must name `orbital` |
| `ORBITAL_INTERVAL` | `1s` | the wait after a response |
| `ORBITAL_SEED` | random (logged at startup) | the draws' seed |
| `orbital.weights` | step 2.0, wait 1.0, throw 1.0 | an unknown name fails startup; 0 = never |
| `orbital.step` | center (5, 4), radius 3.0, arc 0.5 | |
| `orbital.wait.duration` | 1.0 | |
| `orbital.throw` | target the pillar `…0003`, spread 0.2, recovery 2.0, projectile as JSON | the pebble of the old seed |
| `SPRING_PROFILES_ACTIVE`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES` | | as world |

The orbit, target and projectile are orbital's, not game state.
`spring.http.client.connect-timeout` 2 s, `read-timeout` 10 s.

## Tests
`mvn -B -f ../../pom.xml -pl services/orbital -am verify` (needs Docker).
`StubWorld` is world's API on the JDK `HttpServer`, recording each request
with when it came and when it was answered.
- `ChooserTest`: 10,000 seeded draws follow 2:1:1 (within 0.02); the draw's
  `value` is `Draws.pick` of its `u`; a skip lasts exactly a minute; zero and
  bad weights.
- `StepChoiceTest`: the next point, off-orbit positions, no drift over 10,000 steps.
- `PacingTest`: interval, back-off 2, 4, 8, 16, 30 s, reset, 30 s for denied.
- `PlayerTest`: the intent's shape (`ifVersion`, params, `reported`), the key
  on every request, the throw's `at`, every error path, the key in no log line.
- `OrbitalLoopTest`: the next turn starts an interval after the response
  (300 ms, with world answering in 200 ms); stop ends the thread mid-wait.
- `OrbitalAppTest`: the wired service against the stub: health `world` UP,
  the Prometheus counter, one trace id per turn across its requests.
- `PlaysRealWorldTest`: world's jar in its own JVM on a Testcontainers
  Postgres (two Spring apps on one classpath would share one
  `application.yml` and world's auto-configuration), orbital in-process at
  100 ms: within 5 s the orbiter moved and the log has `intent` entries
  `by: {"kind":"service","principal":"orbital"}`.

**How the build finds world's jar.** The `world-it` profile (active unless
`-DskipTests`) declares `scarcity-world` as a test dependency of type `pom`
with every transitive excluded: it only orders the reactor, so `-am`
packages world before these tests and nothing of world is on the classpath.
The jar's path is the surefire property `world.jar`. The Dockerfile passes
`-DskipTests`, so the image builds world-model and orbital only. That is
why `modules.yaml` lists world and its libs in orbital's `paths`.

## Deployment (#115)
`modules.yaml` has `deploy: false` until #115: the deploy job waits on the
rollout of every `deploy: true` module, and `k8s-resources/orbital` doesn't
exist yet. #115 needs:
- the secret `world-api-keys` with data key `orbital: <key>` (at least 16
  characters), mounted into world at `SCARCITY_WORLD_API_KEYS_DIR`, and
  given to orbital as env `ORBITAL_API_KEY` (`secretKeyRef`, key `orbital`);
- `k8s-resources/orbital/` (Deployment `orbital`, **1 replica**: two would
  both play, and mostly get 409s; a Service is only needed for scraping),
  env `WORLD_URL=http://world:8080`, `SPRING_PROFILES_ACTIVE=k8s`, the OTLP
  variables as world; ports 8080 `http`, 8081 `metrics`; liveness and
  readiness on `/actuator/health/liveness|readiness` at 8081;
  requests and limits (a small JVM, as subscriptions);
- `deploy: true` here, and the image pull secret as the other services.

## Open follow-ups
- One instance only: a second replica would play the same entity. If it
  ever needs two, it needs leader election, not more intents.
