# AGENTS.md: services/diver

Plays the diver (#171): the entity whose `controller` is
`{"kind":"service","principal":"diver"}`, the dive game's npc
(`libs/world-seed` `DiveGame.diver`, `seed/diver.json`). It goes down: walk to
the stairs down, take them, again on every level. It is told its turns by the
**turn feed** (`docs/websocket.md`, `{"type":"subscribe","turns":true}`), as
`microbe` is, because the levels it goes to are made after it connects.
Java 25, Spring Boot 3.5.7, package `com.pessimisticit.scarcity.diver`. Depends
on `libs/service-controller` (token, world, socket, turns, chooser; read its
AGENTS.md for a turn, the outcomes and the socket) and `libs/observability`.
Lint applies with no baseline entries.

## What it plays
Each choice is a `@Component` implementing `Choice`. The three never overlap
(`Choice.open`), so every turn has exactly one open choice and its
categorical draw has one weight; the log still records it.
- `Stairway`: the **stairs down** (`Stairs.leadsDown()`, prototype
  `stairs-down`) nearest the diver among the turn area's entities
  (`World.entities`), read once per turn. Stairs up are never a target.
- `step` (`ApproachChoice`), open while those stairs are farther than
  `Traverse.REACH`: straight toward them, at most `diver.step.max-distance`,
  stopping `REACH / 2` short of their centre, so the next turn can take them.
  The diver and the stairs are inside the area, so the point is too. An
  obstacle in the way shortens the step (world casts the line); the next turn
  tries again from there.
- `traverse` (`DescendChoice`), open within `REACH`: takes them.
- `wait` (`WaitChoice`), open only when the level has no stairs down:
  `diver.wait.duration`.

**Wall-clock pause**: `controller.think` is 30 s, so people can watch (an npc
that simulates a player may wait, docs/actions.md "Crossing areas"). The
pause is before every action, the arrival at a new level included; the mice
of a level that is warming up don't wait for it, as they are microbe's.
Metrics `diver.intents{outcome}`, observation `diver.turn`.

## Configuration (`application.yml`: `controller` and `diver`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `AUTH_URL` | `http://localhost:8080` | k8s `http://auth:8080` |
| `DIVER_API_KEY` | | **required**, startup fails without it; k8s: secret `service-api-keys`, key `diver` |
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `SUBSCRIPTIONS_URL` | `ws://localhost:8080/api/subscriptions/ws` | k8s `ws://subscriptions:8080/api/subscriptions/ws` |
| `controller.area` | blank | blank: the turn feed |
| `DIVER_THINK` | `30s` | between a turn frame and acting on it |
| `DIVER_SEED` | random (logged at startup) | the draws' seed |
| `controller.weights` | step, traverse, wait 1.0 | 0 switches one off; the choices never compete |
| `diver.step.max-distance` | 1.0 | metres per step |
| `diver.wait.duration` | 1.0 | |

## Tests
`mvn -B -f ../../pom.xml -pl services/diver -am verify` (needs Docker).
The stubs and `ServiceProcess` are service-controller's test-jar.
- `ChoicesTest`: against `StubWorld`'s area, exactly one choice is open: a
  step of at most the max distance straight at the stairs down, the last one
  stopping within reach, a traverse within reach, never the stairs up, a wait
  with no stairs down; the area is read once per turn.
- `DiverPropertiesTest`: the defaults (the feed, 30 s) bind; bad values fail startup.
- `DiverAppTest`: against the stubs, it subscribes to the feed, and the
  diver's turn becomes a step toward the stairs down.
- `PlaysTheDiveTest`: auth's, world's, subscriptions' and microbe's jars on
  one Testcontainers Postgres, the dive game seeded; diver in-process with no
  pause must step to the stairs down at depth 0, traverse them and arrive at
  depth 1, with no intent invalid.

## Deployment
`k8s-resources/diver/`: Deployment `diver`, **1 replica** (`Recreate`): two
would both be told every turn. No Service. `DIVER_API_KEY` is `secretKeyRef`
key `diver` of `service-api-keys`, made by hand by the k8s agent (done
2026-11-18, auth restarted).

## Open follow-ups
- The bottom: levels go on forever, so the diver never stops. A deepest
  level, or a diver that returns, is for when the game has an end.
- Smarter paths (around obstacles) once levels have walls.
