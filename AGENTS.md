# AGENTS.md: working in the Scarcity monorepo

This file is for every agent (and human) that touches this repo. Read it first,
then read the `AGENTS.md` of the module you are working in.

## The game, in brief

Scarcity is an online-only, turn-based, permadeath roguelike in the tradition of
DCSS. It has procedurally generated levels, items and structure, and actions
take time (some faster than others). What sets it apart:

- **Floats, not integers.** Positions, distances, stats, HP and probabilities are
  floating point. Don't quantise to a grid unless a module explicitly does so.
- **Online only.** All game state lives on the server. Clients render and send
  intents; they never decide outcomes.
- **Information dense.** Every random outcome records the exact distribution and
  parameters it was drawn from, e.g. `{"dist":"gaussian","mean":4.2,"sd":1.1,
  "truncate":[1,5),"value":3.71}`. Logs are detailed and shown to players. Never
  summarise a distribution as "1-5".

Future ideas to keep the design open for: wall-clock-pinned turns (a turn with
no action chosen is skipped) and multiple players in a shared world.

## Agent roles

```
human ⇄ repo agent (this file)        discusses features, splits work across modules,
          │                           owns cross-module contracts, CI, k8s-resources/
          ├─ module lead: auth         owns services/auth; reads services/auth/AGENTS.md
          │     ├─ developer agent     implements one task
          │     └─ tester agent        independently verifies that task
          ├─ module lead: client-web
          │     ├─ developer agent
          │     └─ tester agent
          └─ k8s agent (separate session: k8s-resources-b0)
                                       namespaces, domains, secrets, backups on the cluster
```

- **Repo agent** talks to the human. It owns the root docs, `modules.yaml`,
  `.github/`, `k8s-resources/` and any contract that crosses modules (HTTP APIs,
  resource schemas, JWT claims). It gives each module lead tasks.
- **Module lead** (`.claude/agents/module-lead.md`) oversees one module. For
  each task it starts a **developer** (`module-developer.md`) and then a
  **tester** (`module-tester.md`). The tester gets only the task description
  and the diff, so it judges the result independently. The lead loops until the
  tester passes, then reports back to the repo agent. If the runtime does not let
  the lead spawn agents, the lead does both phases itself, in order, and keeps
  them separate: write the code, then put on the tester hat and try to break it.
- **The k8s agent** lives in its own session (the `k8s-resources` repo). Cluster
  resources outside this namespace (domains, Gateway listeners, certs, secrets,
  storage classes, backup slots) are requested from it. Do not hand-edit cluster
  config.

Module leads stay inside their module directory. When a task needs a change
elsewhere (another module, `k8s-resources/`, CI), they say so in their report
and don't make the change. The repo agent makes those changes or delegates them.

**Follow-ups go in GitHub issues.** When you defer something (a known limitation,
a non-blocking bug, a decision for later), list it in your report. The repo
agent files it as an issue in scarcity-game/scarcity with `area: <module>` and
`priority: low|normal` labels. Before starting work, check the open issues for
your module (`gh issue list --repo scarcity-game/scarcity --label "area: <module>"`).
A report's "open issues" section must not be the only place a follow-up is
recorded.

Agents never commit or push unless the repo agent or the human asks them to.
They never commit secrets. `.secrets/` is gitignored, and the repo is
**public**.

## Architecture

### Modules
A module is one directory with its own `AGENTS.md` and `README.md`:

| kind | lives in | examples |
|---|---|---|
| `service` | `services/<name>` | auth, user-info, world, combat |
| `library` | `libs/<name>` | mapgen, resource-model, distributions |
| `content` | `content/<name>` | music, tilesets, item tables |
| `client` | `clients/<name>` | web (React), desktop (Electron, later) |

Every module is listed in [`modules.yaml`](modules.yaml). CI only builds what is
listed there.

### Defaults
- **Java 25 + Spring Boot 3 + Maven** for services and libraries. Java modules
  are children of the root `pom.xml`. A module may "eject" to another stack
  when it has a reason; record the reason in its `AGENTS.md`.
- **Web client:** React + TypeScript + Vite, served by nginx on port 8080.
- **Persistence:** one Postgres (`postgres-service:5432`) in the namespace. Each
  service owns its own schema (named after the service) and manages it with
  Flyway. Services never read another service's tables; they call its HTTP API.

### Services
- Deployed independently as microservices into the k8s namespace `scarcity`.
- They talk to each other over plain HTTP via k8s Service DNS (`http://auth:8080`).
- When a call is made because a user did something, pass the user's JWT along
  in `Authorization: Bearer …`. Service-initiated calls (e.g. scheduled ticks)
  will get service credentials later; not designed yet.
- Every HTTP service listens on **8080**. Spring services serve actuator
  (health, `/actuator/prometheus`) on a separate management port **8081**,
  named `metrics` in the Deployment, which is never in a Service or route.
  Other services serve `/health` on 8080.
- Public paths are prefixed by the service: `/api/<service>/…`. The Gateway
  routes by longest prefix, and `/` goes to the web client.

### Auth and JWTs
- `auth` mints RS256 JWTs. Claims: `iss=scarcity-auth`, `sub=<user uuid>`,
  `username`, `iat`, `exp`.
- Public keys are published at `GET /api/auth/.well-known/jwks.json`
  (in-cluster: `http://auth:8080/api/auth/.well-known/jwks.json`). Other
  services verify tokens locally against the JWKS (Spring:
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`).

### Resources
Everything in the game context is a **resource**: games, areas, entities and
items.

```json
{
  "id": "area:7f3c…",        // resource id, globally unique, type-prefixed
  "type": "area",
  "stateHash": "b3:9a1f…",    // hash of the canonical state; changes on every mutation
  "children": ["entity:…"],         // contained resources, by id
  "state": { … }              // type-specific
}
```

- Resources contain other resources.

#### Glossary: resource types (decided 2026-11-09)
| type | contains | what it is | example |
|---|---|---|---|
| `game` | areas | one run or world | dungeon 123 |
| `area` | entities | one connected space with geometry | citadel 456 |
| `entity` | items | something that occupies a position in an area. Its `kind` is `mobile` (moves by itself), `fixed` (doesn't move), or `player` (player controlled) | orc 789 |
| `item` | (items, later) | carried or lying around | short sword 012 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`. Use these words in code,
APIs and docs. Don't use "zone", "map", "mob" or "level" for them.
- `GET` (REST) returns the full resource and its current `stateHash`.
- Clients **subscribe** to resource ids over WebSocket and get change
  notifications that carry the new `stateHash` (and a delta when one is
  cheap to send). If the client's hash doesn't match after it applies the
  delta, it does a full REST refresh of that resource.
- The schema and hashing rules will live in `libs/resource-model` (not built
  yet). Until then, treat this section as the contract.

#### Change notification transport: Postgres `LISTEN/NOTIFY` (decided 2026-11-09)
- When a service changes a resource, it calls `pg_notify` in the **same
  transaction**. Postgres only delivers the notification if that transaction
  commits, so clients never see a hash that was rolled back.
- Payload is `{"id","type","stateHash","traceparent"}`. That keeps it well
  under the 8KB NOTIFY limit; clients fetch the full state over REST.
  `traceparent` is the W3C trace context of the transaction that made the
  change (absent if there was none). Subscribers continue the trace from it,
  because NOTIFY has no headers to carry it otherwise.
- A `subscriptions` service holds the client WebSockets. It `LISTEN`s and fans
  notifications out to the sockets subscribed to each id.
- NOTIFY isn't durable, and that's accepted. A missed notification shows up as
  a hash mismatch on the client's next check, and the client refreshes.
- Services publish and subscribe only through an interface in
  `libs/resource-model`, never by calling `pg_notify` directly. That way we can
  move to Redis Streams later (many WebSocket replicas, high fan-out, replay
  after disconnect) without touching every service.
- We considered RabbitMQ and NATS and rejected them for now. They add
  stateful infrastructure we don't need yet.
- WebSocket routes need `timeouts: {request: 0s}` on their HTTPRoute rule,
  because Envoy's default 15s timeout kills them.

### Observability (decided 2026-11-09, tracked in #18)
- **OpenTelemetry** everywhere: export over OTLP, propagate W3C `traceparent`.
  A request should produce one trace from the browser to the database.
- Grafana stack: Tempo (traces), Loki (logs), Prometheus (metrics), Grafana.
  The collector endpoint comes from the k8s agent; don't hard-code it.
- Java services use Spring Boot's Micrometer Tracing with the OTel bridge,
  not the OTel Java agent. Logs include the trace and span ids. Metrics are
  served at `/actuator/prometheus`.
- Pass trace context on every hop: HTTP headers between services, and the
  `traceparent` field across `LISTEN/NOTIFY`.
- Spans and logs are operational data. Game logs (distributions, rolls) are a
  separate, player-facing record.

### Randomness
All randomness goes through one seeded, loggable source. A draw records its
distribution, parameters, bounds and result. That record is game-log data the
client can show. (This will become `libs/distributions`.)

## Deployment and k8s
See [`k8s-resources/README.md`](k8s-resources/README.md) for how a module
registers a deployment. In short: add a `Dockerfile`, add the module to
`modules.yaml` with `deploy: true`, add `k8s-resources/<module>/`, and list it in
`k8s-resources/kustomization.yaml`.

Cluster rules (from the k8s agent):
- The deploy service account has rights in namespace `scarcity` only. Kustomize
  output must **not** contain a Namespace, ClusterRole, CRD or StorageClass.
- The namespace also runs the old `roll` Deployment/Service. Don't reuse that
  name, and don't prune it.
- Images on ghcr are private. Every Deployment sets
  `imagePullSecrets: [{name: ghcr-example-image-pull-secret}]`. That secret is the
  shared glowbox read:packages credential, owned and renewed by the k8s agent.
  Reference it; never recreate or manage it here.
- Every container (Postgres and CronJobs included) sets `resources.requests` **and**
  `resources.limits`. The node is shared and has no swap.
- Secrets are created by hand by the k8s agent and are never committed:
  `postgres-secrets` (`username`, `password`), `auth-jwt` (`private.pem`).

## Conventions
- Small PRs into `main`. CI must be green. A push to `main` deploys.
- Tests sit next to code. Java services use Testcontainers for Postgres, so
  don't use H2 as a stand-in.
- Commit messages are imperative and name the module scope: `auth: add users table`.
- Shell snippets in docs are multiline, one command per line.
