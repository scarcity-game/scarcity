# AGENTS.md: services/orbital

Plays the entities whose `controller` is `{"kind":"service","principal":"orbital"}`
(the test area's orbiter): a **controller** (`docs/actions.md`, "Controllers",
"Intents", "Turns") that waits to be told its entity is the active entity, reads it, decides,
and posts an intent, through the same API and WebSocket a player uses. Java
25, Spring Boot 4.1, package `com.pessimisticit.scarcity.orbital`. Depends
on `libs/service-controller` (the token, world, the socket, the turns) and
`libs/observability`. Lint applies with no baseline entries. Part of #103 (#114).

## What it plays
Turns, the token, the socket and the outcomes are
[`libs/service-controller`](../../libs/service-controller/AGENTS.md)'s; orbital
sets `controller.principal: orbital` and adds its choices, each a
`@Component` implementing `Choice`, weighted in `controller.weights`:
- `step` (`StepChoice`): to the point `arc / radius` further round the
  circle from the entity's **actual** angle around `center`, so a short
  step doesn't drift the radius. Not feasible when that step isn't clear
  this turn (`ClearStep.clear`: a mouse in the way, say).
- `wait` (`WaitChoice`): `duration`.
- `throw` (`ThrowChoice`): `at` the target's position among the turn's
  entities, with `spread`, `recovery` and the projectile (read at startup;
  a bad one fails startup). Not feasible when the target isn't in the area.

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
| `SPRING_PROFILES_ACTIVE`, `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES` | | as world |

The orbit, target and projectile are orbital's, not game state. Bad values
fail startup naming their key (`OrbitalPropertiesTest`).
`spring.http.client.connect-timeout` 2 s, `read-timeout` 10 s.

## Tests
`mvn -B -f ../../pom.xml -pl services/orbital -am verify`.
The stubs are service-controller's test-jar.
- `StepChoiceTest`: the next point, off-orbit positions, no drift over 10,000 steps.
- `OrbitalPropertiesTest`: the defaults bind; each bad value fails startup.
- `PlayerTest`: the intent's shape (`ifVersion`, params, `reported`), the
  token on every request, the throw's `at`, every error path, a 401 fetching
  a new token, the key and token in no log line.
- `OrbitalAppTest`: the wired service against the stubs: a repeated turn
  frame becomes one intent after the thinking time, all of it in one trace;
  health `subscription`; the Prometheus counter.

There is no end-to-end test here: with other controllers in the test room's turn queue the
area stands still on their turns (no turn timeout yet, #151). The real game loop is covered by
`services/diver` `PlaysTheDiveTest` and `services/microbe` `PlaysTheMiceTest`.
