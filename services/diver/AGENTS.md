# AGENTS.md: services/diver

Plays the diver (#171): the entity whose `controller` is
`{"kind":"service","principal":"diver"}`, the dive game's npc
(`libs/world-seed` `DiveGame.diver`, `seed/diver.json`). It goes down: walk to
the stairs down, take them, again on every level. It is told its turns by the
**turn feed** (`docs/websocket.md`, `{"type":"subscribe","turns":true}`), as
`microbe` is, because the levels it goes to are made after it connects.
Java 25, Spring Boot 4.1, package `com.pessimisticit.scarcity.diver`. Depends
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
  tries again from there. Closed when the step would start touching something
  it moves toward (a contact, #279: the mouse between it and the stairs):
  world rejects that with 422 (`Step.validate`), so the diver checks the same
  thing (`Stairway.blocked`, `Contacts.first` over the turn's entities, time
  <= 0, heading only) before it plans.
- `traverse` (`DescendChoice`), open within `REACH`: takes them.
- `wait` (`WaitChoice`), open when the level has no stairs down, or when the
  step toward them is blocked by a contact (above): `diver.wait.duration`.
  Turns are strictly ordered, so waiting is what gives the touched entity its
  turn; without it the area deadlocks.

**Wall-clock pause**: `diver.arrival-pause` (30 s), so people can watch (an npc
that simulates a player may wait, docs/actions.md "Crossing areas"). It is
taken once per area, on the diver's first turn after it arrives there (the
area at startup included, `ArrivalPause`, a `TurnDelay` of
libs/service-controller); later turns in that area act at once, and a new
area pauses again. `controller.think` is 0. The mice of a level that is
warming up don't wait for it, as they are microbe's.
Metrics `diver.intents{outcome}`, observation `diver.turn`.

## Configuration (`application.yml`: `controller` and `diver`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `AUTH_URL` | `http://localhost:8080` | k8s `http://auth:8080` |
| `DIVER_API_KEY` | | **required**, startup fails without it; k8s: secret `service-api-keys`, key `diver` |
| `WORLD_URL` | `http://localhost:8080` | k8s `http://world:8080` |
| `SUBSCRIPTIONS_URL` | `ws://localhost:8080/api/subscriptions/ws` | k8s `ws://subscriptions:8080/api/subscriptions/ws` |
| `controller.area` | blank | blank: the turn feed |
| `DIVER_ARRIVAL_PAUSE` | `30s` | wall-clock pause on the first turn in each area; `controller.think` is 0 |
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
  with no stairs down or touching an entity between it and them (not touching, or
  with it behind, it steps); the area is read once per turn.
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
