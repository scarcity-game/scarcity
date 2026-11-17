# AGENTS.md: services/orbital

Plays the entities whose `controller` is `{"kind":"service","principal":"orbital"}`
(the test area's orbiter): a **controller** (`docs/actions.md`, "Controllers",
"Intents", "Turns") that waits to be told its entity is the active entity, reads it, decides,
and posts an intent, through the same API and WebSocket a player uses. Java
25, Spring Boot 3.5.7, package `com.pessimisticit.scarcity.orbital`. Depends
on `libs/service-controller` (the token, world, the socket, the turns) and
`libs/observability`. Lint applies with no baseline entries. Part of #103 (#114).

## What it plays
Turns, the token, the socket and the outcomes are
[`libs/service-controller`](../../libs/service-controller/AGENTS.md)'s; orbital
sets `controller.principal: orbital` and adds its choices, each a
`@Component` implementing `Choice`, weighted in `controller.weights`:
- `step` (`StepChoice`): to the point `arc / radius` further round the
  circle from the entity's **actual** angle around `center`, so a short
  step doesn't drift the radius.
- `wait` (`WaitChoice`): `duration`.
- `throw` (`ThrowChoice`): `GET` the target, `at` its position, with
  `spread`, `recovery` and the projectile (read at startup; a bad one
  fails startup). A 404 target is `Choice.Unavailable`.

Its metrics are `orbital.intents{outcome}`, its observation `orbital.turn`.

## Configuration (`application.yml`: `controller` and `orbital`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `AUTH_URL` | `http://localhost:8080` | k8s `http://auth:8080` |
| `ORBITAL_API_KEY` | | **required**, startup fails without it; k8s: from secret `service-api-keys`, key `orbital` |
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `SUBSCRIPTIONS_URL` | `ws://localhost:8080/api/subscriptions/ws` | k8s `ws://subscriptions:8080/api/subscriptions/ws` |
| `ORBITAL_AREA` | the test area `area:…0002` | `controller.area`: the area subscribed to |
| `ORBITAL_THINK` | `1s` | between a turn frame and acting on it |
| `controller.reconnect`, `reconnect-max`, `silence` | 1 s, 30 s, 90 s | the socket |
| `ORBITAL_SEED` | random (logged at startup) | the draws' seed |
| `controller.weights` | step 2.0, wait 1.0, throw 1.0 | an unknown name fails startup; 0 = never |
| `orbital.step` | center (5, 4), radius 3.0, arc 0.5 | |
| `orbital.wait.duration` | 1.0 | |
| `orbital.throw` | target the pillar `…0003`, spread 0.2, recovery 2.0, projectile as JSON | the pebble of the old seed |
| `SPRING_PROFILES_ACTIVE`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES` | | as world |

The orbit, target and projectile are orbital's, not game state. Bad values
fail startup naming their key (`OrbitalPropertiesTest`).
`spring.http.client.connect-timeout` 2 s, `read-timeout` 10 s.

## Tests
`mvn -B -f ../../pom.xml -pl services/orbital -am verify` (needs Docker).
The stubs are service-controller's test-jar.
- `StepChoiceTest`: the next point, off-orbit positions, no drift over 10,000 steps.
- `OrbitalPropertiesTest`: the defaults bind; each bad value fails startup.
- `PlayerTest`: the intent's shape (`ifVersion`, params, `reported`), the
  token on every request, the throw's `at`, every error path, a 401 fetching
  a new token, the key and token in no log line.
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

## Deployment
`k8s-resources/orbital/`: Deployment `orbital`, **1 replica** (`Recreate`):
two would both be told every turn, and one would get 409s. No Service: nothing
calls it, and Prometheus scrapes the pod's `metrics` port. Env `AUTH_URL`,
`WORLD_URL`, `SUBSCRIPTIONS_URL`, `SPRING_PROFILES_ACTIVE=k8s` and the OTLP
variables; `ORBITAL_API_KEY` is `secretKeyRef` key `orbital` of the secret
`service-api-keys`, which auth mounts whole at `AUTH_SERVICE_KEYS_DIR`. The
secret is made by hand by the k8s agent; auth reads it at startup, so a new
principal's key needs an auth restart.

## Open follow-ups
- One instance only: a second replica would be told the same turns. If it
  ever needs two, it needs leader election, not more intents.
