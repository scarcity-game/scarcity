# AGENTS.md: services/microbe

Plays the mice: every entity whose `controller` is
`{"kind":"service","principal":"microbe"}`, in every area (#170). microbe is an
**intelligence service** (docs/actions.md, #376): it holds no WebSocket and
calls nobody; world POSTs it each mouse's turn (`POST /turn`, a `TurnRequest`)
and the answer is the step or wait. `controller.mode: endpoint`
(libs/service-controller, which has the endpoint, the token check and the
planning; read its AGENTS.md). It keeps no state between turns, so any replica
answers any turn. Java 25, Spring Boot 4.1, package
`com.pessimisticit.scarcity.microbe`. Depends on `libs/service-controller`,
`libs/observability` and `spring-security-oauth2-jose` (to verify world's
token). Lint applies with no baseline entries. One service per kind of
creature: the next mob kinds (`direct`, `cluster`, `intelligent`, `natural`,
in #170's comments) follow this module.

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

Metrics `microbe.intents{outcome}`, observation `microbe.turn`.

## Configuration (`application.yml`: `controller` and `microbe`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `controller.mode` | `endpoint` | fixed in `application.yml` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | auth's keys, to verify world's token (`kind=service`, `sub=world`); k8s `http://auth:8080/api/auth/.well-known/jwks.json` |
| `MICROBE_SEED` | random (logged at startup) | the draws' seed; unset in the cluster |
| `controller.weights` | step 3.0, wait 1.0 | an unknown name fails startup; 0 = never |
| `microbe.step.distance` | 1.0 | |
| `microbe.wait.duration` | 1.0 | |

There is no API key, `WORLD_URL`, `SUBSCRIPTIONS_URL` or think time any more.

## Tests
`mvn -B -f ../../pom.xml -pl services/microbe -am verify` (no Docker).
`TestJwks` is service-controller's test-jar.
- `ScurryChoiceTest`: the step follows the reported heading; in a walled room,
  over 2,000 random positions, more than 99% of turns have a clear step and
  every step planned is clear.
- `MicrobePropertiesTest`: the defaults (endpoint mode, JWKS) bind; bad
  values fail startup.
- `MicrobeAppTest`: the wired service, `POST /turn` with a token from a test
  JWKS: a mouse's turn becomes one step with `reported.plan.heading.1`; no token
  is 401, another principal or a `sub=world` without `kind=service` is 403;
  no subscription health indicator.
- There is no end-to-end test here any more (`PlaysTheMiceTest` needed the feed).
  world dispatching to microbe is covered by world's tests and
  `services/diver`'s `PlaysTheDiveTest`, which starts microbe's jar before world.

## Deployment
`k8s-resources/microbe/`: Deployment `microbe`, **2 replicas**, `RollingUpdate`
(`maxSurge: 1`, `maxUnavailable: 0`), readiness on the actuator (8081), and a
Service `microbe:8080` that world calls. `AUTH_JWKS_URI` is the only
setting from the environment; no secret.

## Open follow-ups
- Later behaviours from #170: flee harm, attack food.
