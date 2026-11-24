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
(each is feasible only when the others aren't: `Choice.Plan.none` otherwise), so
every turn has exactly one feasible choice and its
categorical draw has one weight; the log still records it.
- `Stairway`: the **stairs down** (`Stairs.leadsDown()`, prototype
  `stairs-down`) nearest the diver among the turn area's entities
  (the turn's `Choice.Situation`, read once per turn by service-controller). Stairs up are never a target.
  `Stairway.route` is the way there, planned once per turn over the same
  entities with `libs/pathfinding` (#237): waypoints round every wall, mouse
  and other obstacle as they are now.
- `step` (`ApproachChoice`), feasible while those stairs are farther than
  `Traverse.REACH` and there is a way: toward the route's first waypoint, at
  most `diver.step.max-distance`; on the last leg it stops `REACH / 2` short
  of the stairs' centre, so the next turn can take them. Each leg is one that
  world's `Contacts.first` doesn't interrupt, so the step is never rejected
  for starting against what it moves toward (#279). A step cut short (a mouse
  moved into the way) is planned again from where it stopped.
- `traverse` (`DescendChoice`), feasible within `REACH`: takes them.
- `wait` (`WaitChoice`), feasible when the level has no stairs down, or when there
  is no way to them (a mouse in a gap too narrow to pass it):
  `diver.wait.duration`. Turns are strictly ordered, so waiting is what gives
  the entity in the way its turn; without it the area deadlocks.

**Wall-clock pause**: `diver.arrival-pause` (5 s), so people can watch (an npc
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
| `DIVER_ARRIVAL_PAUSE` | `5s` | wall-clock pause on the first turn in each area; `controller.think` is 0 |
| `DIVER_SEED` | random (logged at startup) | the draws' seed |
| `controller.weights` | step, traverse, wait 1.0 | 0 switches one off; the choices never compete |
| `diver.step.max-distance` | 1.0 | metres per step |
| `diver.wait.duration` | 1.0 | |

## Tests
`mvn -B -f ../../pom.xml -pl services/diver -am verify` (needs Docker).
The stubs and `ServiceProcess` are service-controller's test-jar.
- `ChoicesTest`: against `StubWorld`'s area, exactly one choice is feasible: a
  step of at most the max distance toward the stairs down (straight in the
  open, round a wall or a mouse in the way), the last one stopping within
  reach, a traverse within reach, never the stairs up, a wait with no stairs
  down or no way to them (walled in); the way is planned once per turn.
- `DiverPropertiesTest`: the defaults (the feed, 5 s) bind; bad values fail startup.
- `DiverAppTest`: against the stubs, it subscribes to the feed, and the
  diver's turn becomes a step toward the stairs down.
- `PlaysTheDiveTest`: auth's, world's, subscriptions' and microbe's jars on
  one Testcontainers Postgres, the dive game seeded, every level generated
  from world's `WORLD_RANDOM_SEED` (311); diver in-process with no pause must
  find its way round the walls to the stairs down, level after level, and
  arrive at depth 3, with no intent invalid.

## Deployment
`k8s-resources/diver/`: Deployment `diver`, **1 replica** (`Recreate`): two
would both be told every turn. No Service. `DIVER_API_KEY` is `secretKeyRef`
key `diver` of `service-api-keys`, made by hand by the k8s agent (done
2026-11-18, auth restarted).

## Open follow-ups
- The bottom: levels go on forever, so the diver never stops. A deepest
  level, or a diver that returns, is for when the game has an end.
