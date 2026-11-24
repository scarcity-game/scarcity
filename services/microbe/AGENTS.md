# AGENTS.md: services/microbe

Plays the mice: every entity whose `controller` is
`{"kind":"service","principal":"microbe"}`, in every area (#170). The mice
are the mobs of each level (`libs/world-seed` `Levels.mouse`), so microbe
is told their turns by the **turn feed** (`docs/websocket.md`,
`{"type":"subscribe","turns":true}`): `controller.area` is blank, and
subscriptions joins it to every area one of its mice is up in, levels made
after it connected included. Java 25, Spring Boot 4.1, package
`com.pessimisticit.scarcity.microbe`. Depends on `libs/service-controller`
(token, world, socket, turns, chooser; read its AGENTS.md for a turn, the
outcomes and the socket) and `libs/observability`. Lint applies with no
baseline entries. One service per kind of creature: the next mob kinds
(`direct`, `cluster`, `intelligent`, `natural`, in #170's comments) follow
this module.

## What it plays
Each choice is a `@Component` implementing `Choice`, weighted in
`controller.weights`:
- `step` (`ScurryChoice`): `microbe.step.distance` along a heading drawn
  uniformly from `[0, 2π)` (`Draws.uniform`) that is clear this turn
  (service-controller's `ClearStep.anyHeading`: up to 10 headings, the first
  whose step meets no wall or mouse before it ends). Each heading tried is the
  intent's `reported.plan.heading.<n>`, the last the one taken, so the log has
  every draw. With none clear, step isn't feasible this turn and the mouse
  waits; it tries again when it is up next (#316).
- `wait` (`WaitChoice`): `microbe.wait.duration`.

No wall-clock pause (`controller.think: 0s`): an arrival in a level waits
for its mice (docs/actions.md, "Turns"), so they decide at once. Metrics
`microbe.intents{outcome}`, observation `microbe.turn`.

## Configuration (`application.yml`: `controller` and `microbe`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `AUTH_URL` | `http://localhost:8080` | k8s `http://auth:8080` |
| `MICROBE_API_KEY` | | **required**, startup fails without it; k8s: secret `service-api-keys`, key `microbe` |
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `SUBSCRIPTIONS_URL` | `ws://localhost:8080/api/subscriptions/ws` | k8s `ws://subscriptions:8080/api/subscriptions/ws` |
| `controller.area` | blank | blank: the turn feed |
| `MICROBE_THINK` | `0s` | between a turn frame and acting on it |
| `MICROBE_SEED` | random (logged at startup) | the draws' seed |
| `controller.weights` | step 3.0, wait 1.0 | an unknown name fails startup; 0 = never |
| `microbe.step.distance` | 1.0 | |
| `microbe.wait.duration` | 1.0 | |

## Tests
`mvn -B -f ../../pom.xml -pl services/microbe -am verify` (needs Docker).
The stubs and `ServiceProcess` are service-controller's test-jar.
- `ScurryChoiceTest`: the step follows the reported heading; in a walled room,
  over 2,000 random positions, more than 99% of turns have a clear step and
  every step planned is clear.
- `MicrobePropertiesTest`: the defaults (the feed, no pause) bind; bad
  values fail startup.
- `MicrobeAppTest`: against the stubs, it subscribes to the feed, and a
  mouse's turn becomes one step with `reported.plan.heading.1`.
- `PlaysTheMiceTest`: auth's, world's and subscriptions' jars on one
  Testcontainers Postgres, the dive game seeded; the test plays the diver
  (an npc keeps the area running) and microbe, with no area configured, must
  post at least three intents for the mouse, none invalid.

## Deployment
`k8s-resources/microbe/`: Deployment `microbe`, **1 replica** (`Recreate`):
two would both be told every turn. No Service. `MICROBE_API_KEY` is
`secretKeyRef` key `microbe` of `service-api-keys`, made by hand by the k8s
agent; auth reads the secret at startup, so the new key needs an auth
restart. Until the key exists the pod can't start.

## Open follow-ups
- Later behaviours from #170: flee harm, attack food.
- The feed keeps every area it joined subscribed; many levels per game may
  need it to leave areas nobody is in (decisions.md, 2026-11-17).
