# AGENTS.md: services/brawler

Plays the fighters: every entity whose `controller` is
`{"kind":"service","principal":"brawler"}`, in every area (#454, epic #449).
brawler is an **intelligence service**, built like `services/microbe`
(docs/actions.md, "Intelligence services"). It holds no WebSocket and calls
nobody. world POSTs it each fighter's turn (`POST /turn`, a `TurnRequest`),
and the answer is a strike, a step or a wait. Configuration:
`controller.mode: endpoint` (libs/service-controller has the endpoint, the
token check and the planning; read its AGENTS.md). It keeps no state between
turns, so any replica answers any turn.

Java 25, Spring Boot 4.1, package `com.pessimisticit.scarcity.brawler`.
- Depends on `libs/service-controller`, `libs/combat` (`Strike`'s `reach`,
  `gap`, `specFor`), `libs/pathfinding`, `libs/observability` and
  `spring-security-oauth2-jose`.
- Lint applies with no baseline entries.

## Whom it fights
The **quarry** (`Quarry`) is the nearest creature in the turn's range that the
fighter is hostile to under the turn's `factions`, the game's relations
(docs/resources.md, "Factions"; `Factions.hostile`). That means either:
- one of its `foes`, or
- a creature whose side (its faction, or `player`) is in its own faction's
  `hostileTo`.

A creature is a mob, an npc or a player that is damageable and has a radius; never a fixed entity (a damageable pillar). Ties on distance are broken
by id. Kin of the same faction are never the quarry unless they attacked it.

## What it plays
Each choice is a `@Component` implementing `Choice`. The choices never overlap:
exactly one is feasible each turn, and the weights in `controller.weights` only
switch one off (0).
- `strike` (`StrikeChoice`): the quarry, when it is within reach edge to edge
  (`Strike.gap <= Strike.reach`).
- `step` (`ChaseChoice`): when the quarry is out of reach and there is a way.
  - The way is `Pathfinder.path` to the **stand point**, where the gap is half
    the fighter's reach, on the line from the quarry to the fighter.
  - It steps toward the first waypoint, at most `Step.reach` and at most
    `brawler.step.max-distance`.
  - The way is planned again every turn.
- `wait` (`WaitChoice`): with no quarry, or no way to it.

The quarry and its way are found once per turn and shared by the choices,
like diver's `Stairway`.

Metrics `brawler.intents{outcome}`, observation `brawler.turn`.

## Configuration (`application.yml`: `controller` and `brawler`, `@Validated`)
| env var / key | default | meaning |
|---|---|---|
| `controller.mode` | `endpoint` | fixed in `application.yml` |
| `AUTH_JWKS_URI` | `http://localhost:8080/api/auth/.well-known/jwks.json` | auth's keys, to verify world's token; k8s `http://auth:8080/api/auth/.well-known/jwks.json` |
| `BRAWLER_SEED` | random (logged at startup) | the draws' seed; none of brawler's choices draws yet |
| `controller.weights` | strike, step, wait 1.0 | 0 switches one off |
| `brawler.step.max-distance` | 2.0 | the longest step it plans |
| `brawler.wait.duration` | 1.0 | |

## Tests
`mvn -B -f ../../pom.xml -pl services/brawler -am verify` (no Docker).
- `ChoicesTest`:
  - exactly one choice is feasible each turn
  - a quarry in reach is struck; one out of reach is chased; the last step stops in reach
  - it goes for the nearest
  - kin and sides its faction isn't hostile to are left alone (under the arena's relations and under the standard ones)
  - a neutral strikes back at a foe
  - with nobody to fight, it waits
- `BrawlerAppTest`: the wired service, `POST /turn` with a token from a test
  JWKS.
  - A goblin's turn beside a beast, under arena relations, becomes a strike.
  - No token is 401; another principal is 403.

## Deployment
`k8s-resources/brawler/`:
- Deployment `brawler`, 1 replica (only arenas have fighters), `RollingUpdate`
  (`maxSurge: 1`, `maxUnavailable: 0`).
- Readiness on the actuator (8081).
- A Service `brawler:8080`, registered with world as
  `WORLD_INTELLIGENCE_SERVICES_BRAWLER_*` (range 8.0).
- No secret: world calls brawler, never the other way round.

## Open follow-ups
- Dens spawn mobs with `spawn.controller`; the arena (#455) sets it to
  brawler.
- `foes` isn't pruned (docs/decisions.md, 2026-11-27).
