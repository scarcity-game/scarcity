# AGENTS.md: working in the Scarcity monorepo

The rules for every agent (and human) in this repo. Contracts live in
`docs/`; read only what your task needs:
1. This file, then the `AGENTS.md` of the module you work in.
2. [`docs/resources.md`](docs/resources.md) if the module reads, writes or
   subscribes to game state.
3. [`docs/actions.md`](docs/actions.md) if it changes game state or shows what
   resources are doing; [`docs/action-types.md`](docs/action-types.md) if it
   implements or runs the rules (`libs/world-model`, `libs/world-engine`);
   [`docs/log.md`](docs/log.md) if it writes game state or shows history.
4. [`docs/websocket.md`](docs/websocket.md) for the subscription WebSocket.
5. [`k8s-resources/README.md`](k8s-resources/README.md) if it deploys.
6. [`docs/decisions.md`](docs/decisions.md) (recent and in progress) and
   [`docs/decisions-settled.md`](docs/decisions-settled.md) only to learn why
   a rule exists or before changing one.

## The game, in brief
Scarcity is an online-only, turn-based, permadeath roguelike in the tradition
of DCSS: procedurally generated levels, items and structure, and actions that
take game time.
- **Floats, not integers.** Positions, distances, stats, HP and probabilities
  are floating point. Don't quantise to a grid unless a module says so.
- **Online only.** All game state lives on the server. Clients render and send
  intents; they never decide outcomes.
- **Information dense.** Every random outcome records its exact distribution
  and parameters, e.g. `{"dist":"gaussian","mean":4.2,"sd":1.1,
  "truncate":[1,5),"value":3.71}`. Logs are shown to players. Never summarise
  a distribution as "1-5".
- **Game time passes only when someone acts.** An area advances when a
  controller (a player, or a service such as `orbital`) sends an intent, and
  stands still otherwise (`docs/actions.md`). Keep the design open for
  several players in a shared world.

## How work is done
- **Complicated work** (design decisions, several modules, contracts, auth or
  secrets) is done by the **root agent** that talks to the human, in the main
  checkout, on one branch per ticket, so the human can review in their IDE.
- **Smaller work** goes to one **developer** subagent (`.claude/agents/developer.md`,
  Sonnet) started with `isolation: "worktree"`. It implements, runs the
  module's tests, pushes its branch, opens the PR and answers review
  comments. The root agent doesn't rerun its tests or review its diff; if the
  process fails, the human moves the work up to the root agent.
- **Every change is a PR** the human reviews. CI runs on the PR; nothing is
  merged on a red CI. The job `ci` is the required check on `main`, so the
  human may turn on auto-merge after reviewing; agents never do. A push to
  `main` deploys. Merged branches are deleted.
- **A PR that changes what the UI shows has screenshots** in its description:
  each view or state it changes, at a wide and a narrow width, with a
  "before" too when it changes something that exists. Take them in a real
  browser (`clients/web/AGENTS.md`, "Screenshots"), push the images to the
  `pr-assets` branch under `<pr number>/`, never to the PR's branch, and
  link them by their `raw` URL. Add new screenshots when a review changes
  the UI. A PR without them isn't ready for review.
- Never commit with `git commit -a` or `git add -A`; name the paths. Never
  push to `main`. Never commit secrets: the repo is **public**.
- **Follow-ups go in GitHub issues** in scarcity-game/scarcity, with
  `area: <module>` and `priority: low|normal` labels, mentioned in the PR.
  Check the open issues for your module before starting.
- **Keep tool output small**; it is most of an agent's cost. Maven with
  `-B -ntp -q` piped through `grep -E 'Tests run:|FAIL|ERROR|BUILD' | tail -30`,
  npm through `tail -30`, logs with `--tail`, `grep -n` then read a range
  instead of `cat`ing a big file.
- Every agent role pins `model: sonnet`; don't pass another model unless the
  human asks.
- The **k8s agent** (separate session, `k8s-resources` repo) owns everything
  on the cluster outside this namespace: domains, Gateway listeners, certs,
  secrets, storage classes, backups. Ask it; never hand-edit cluster config.

## Architecture

### Modules
A module is one directory with its own `AGENTS.md` and `README.md`, listed in
[`modules.yaml`](modules.yaml) (CI builds only what is listed there):
`services/<name>`, `libs/<name>`, `content/<name>`, `clients/<name>`.

The game rules are split one way (the engine never touches the database):

| module | holds | depends on |
|---|---|---|
| `libs/observability` | tracing, metrics, semconv span attributes, shared config (Spring auto-configuration) | none |
| `libs/world-model` | typed resource objects, the `Action`/`Effect` interfaces and catalogue, draws. Pure Java, Jackson only | none |
| `libs/world-engine` | the advance: events, effects, log entries in memory. Pure | world-model |
| `libs/pathfinding` | paths of straight legs round an area's obstacles (walls, entities), for controllers: A* over floor cells within a budget, pulled tight through their openings, each leg checked with the rules' `Contacts` | world-model |
| `libs/world-db` | loading and committing an area, the area log tables, the lock, publishing | world-engine, resource-model |
| `libs/world-seed` | the test area definition and seeding | world-model, resource-model |
| `services/world` | HTTP (resources, log, intents), security, wiring | all of the above |
| `libs/service-controller` | what a service that plays entities shares: service token, world client, subscriptions socket, turns, weighted choices (Spring auto-configuration) | world-model |
| `services/orbital` | plays the test area's orbiter: told its turns over the subscriptions WebSocket, acts through the intents API | service-controller, observability |
| `services/microbe` | plays the mice in every area: told their turns by the subscriptions turn feed, steps them randomly or waits | service-controller, observability |
| `services/diver` | plays the dive game's diver: told its turns by the turn feed, walks to the stairs down (round walls) and takes them, level after level | service-controller, pathfinding, observability |
| `content/sprites` | SVG sprites by category and variant, and the manifest mapping prototypes to variants (setting: `docs/setting.md`) | none (its test reads the prototype JSON of `libs/world-model` and `libs/world-seed`) |

Rules are objects, not branches: every action, effect, behaviour kind and
controller kind is one implementation of an interface in `libs/world-model`,
found by name in the catalogue. A new action is one new class and no `if`
anywhere else.

### Defaults
- **Java 25 + Spring Boot 4 + Maven**, children of the root `pom.xml`. Maven
  must run on JDK 25 (`mvn -v`; if not, set `JAVA_HOME` as in `README.md`); `.mvn/maven.config`
  sets `-B -ntp` (no download lines); add `-q` to quieten more. Service
  Dockerfiles build from the repo root with `-pl <module> -am` and start with
  one shared dependency stage, which copies every module's pom (Maven needs
  them all). A new Java module adds its `COPY` line to
  `config/docker/maven-deps.Dockerfile` and runs
  `config/docker/maven-deps.py --write` (`config/docker/README.md`).
- **Java lint is part of `verify`**: Checkstyle and PMD with complexity limits
  (`config/lint/README.md`). A method over a limit gets split, not suppressed.
  Baseline entries may be deleted, never added without the human's OK.
- **Configuration is checked with Bean Validation**: `@ConfigurationProperties`
  records are `@Validated` with `jakarta.validation` constraints
  (`spring-boot-starter-validation`), not hand-written checks in constructors.
- **Web client:** React + TypeScript + Vite, served by nginx on 8080.
- **Persistence:** one Postgres (`postgres-service:5432`). Each service owns a
  schema named after it, managed with Flyway, and never reads another
  service's tables. Tests use Testcontainers, never H2.
- **Names:** a non-static field, or a constructor parameter (a record component
  is one), whose type is defined in this project is named for the class
  (`AreaState areaState`), unless a class has two of one type. Locals are free.
  Record components are JSON keys, so this is also the wire format.
- Tests sit next to code. Commit messages are imperative with the module
  scope: `auth: add users table`. Shell snippets in docs are multiline.
- A cross-module decision gets a dated entry in `docs/decisions.md` (settled
  ones move to `docs/decisions-settled.md`) and, if it
  changes a contract, an update to the matching `docs/` file.

### Services
- Deployed independently into k8s namespace `scarcity`; they call each other
  over HTTP via Service DNS (`http://auth:8080`).
- Every HTTP service listens on **8080**. Spring services serve actuator on
  management port **8081** (named `metrics`, never in a Service or route);
  others serve `/health` on 8080.
- Public paths are `/api/<service>/…`; the Gateway routes by longest prefix
  and `/` goes to the web client.
- A call made because a user did something passes the user's JWT on
  (`Authorization: Bearer …`).

### Auth
- `auth` mints RS256 JWTs: `iss=scarcity-auth`, `sub=<user uuid>`,
  `username`, `iat`, `exp`. Keys at `GET /api/auth/.well-known/jwks.json`;
  services verify locally (Spring: `jwk-set-uri`).
- **Service principals** (such as `orbital`) exchange an API key for a JWT:
  `POST /api/auth/service-token` with `X-Api-Key: <key>` returns
  `{token, expiresIn}` (15 min), with `sub=<principal>`, `kind=service` and no
  `username`. Only `auth` holds keys (k8s secret `service-api-keys`). The
  service uses the token like a user's, on REST and on the WebSocket, and
  renews it before `exp`. A principal reads like a user and posts intents only
  for entities whose `controller` names it. Keys are never logged, not even
  hashed.

### Resources (contract: `docs/resources.md`)
Everything in the game is a **resource**: `game ⊃ area ⊃ entity ⊃ item`, and
an area's `cell`s (its floor plan; walls name theirs), each flat JSON, `{resourceKind, id, version, children, …its own keys}`, with a
kind-prefixed id (`area:7f3c…`); there is no `state` object. In Java a
resource is a typed class hierarchy in `libs/world-model` (`Resource`, then
`Game`, `Area`, `Entity`), read by `resourceKind`, `entityKind` and
`prototype`. Never "zone", "map" or "level".
- `version` is an integer, +1 per change in the writing transaction.
- Reads go through `/api/world/resources`, with the version as `ETag`.
- Changes are published over Postgres `LISTEN/NOTIFY`, one batch per area per
  advance, **only through `libs/resource-model`**. Clients get them from
  `subscriptions` over the WebSocket.
- An advance is one transaction; the area is the unit of ownership.

### Observability
OpenTelemetry everywhere over OTLP, with W3C `traceparent` on every hop (HTTP
headers, and a field across `LISTEN/NOTIFY`). Java services use Micrometer
Tracing with the OTel bridge, not the Java agent; logs carry trace and span
ids; metrics at `/actuator/prometheus`. Java services get all of it from
`libs/observability` (one dependency and one `spring.config.import`); never
copy it into a service. The collector endpoint comes from the k8s agent. Spans and logs are operational; game logs are a separate,
player-facing record.

### Randomness
All randomness goes through one seeded source, and every draw records its
distribution, parameters, bounds and result as game-log data.
**Resource generation is random when possible:** when the game makes an entity
or item from a prototype, its physical values (size, mass, speed, ...) are
drawn around the prototype's, not copied (`Draws.vary`), each draw recorded.

## Deployment
A push to `main` deploys every changed `deploy: true` module.
`k8s-resources/README.md` has how to register a deployment and the cluster's
hard constraints (namespace-scoped kustomize output, the `roll` Deployment,
the image pull secret, requests and limits on every container).
