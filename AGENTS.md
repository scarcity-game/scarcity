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

**Models.** Every agent role pins `model: sonnet` in its frontmatter
(`.claude/agents/`): module lead, developer and tester all run on Sonnet. Don't
pass a different `model` when spawning one unless the human asks.

**Usage (2026-11-11).** Tool output, not reasoning, is most of an agent task's
cost. The agent definitions tell every role to keep output small (`mvn -B -q`
piped through `grep`/`tail`, `--tail` on logs, no `cat` of big files, summary
lines in reports). A lead stops after **two failed tester rounds** and reports
instead of looping; the repo agent decides what happens next.

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

**Worktrees (policy, 2026-11-11).** Every unit of module work runs in its **own
new git worktree**, never in the main checkout. Several agents share one
checkout otherwise, and a commit from one of them sweeps up another's unfinished
files. (That happened once, and `main` auto-deploys.)
- The repo agent spawns each module lead with `isolation: "worktree"`. The
  lead's developer and tester work in that same worktree, one after the other.
  A new task gets a new worktree, not a reused one.
- A module lead never touches the main checkout or another worktree. If it finds
  itself in the main checkout (`git rev-parse --git-dir` is a plain `.git`
  rather than `…/worktrees/…`), it stops and reports.
- A lead may commit to **its own worktree branch**, but never pushes, never
  touches `main` and never merges. It reports the branch name and the worktree
  path.
- The repo agent reviews the branch's diff, makes sure it stays inside the
  module (plus agreed contract files), then merges it into `main` with
  `git merge --ff-only` (rebase the branch first if `main` moved) and pushes.
  It removes the worktree and branch afterwards. A branch that fails its tester
  isn't merged.
- Never commit with `git commit -a` or `git add -A` in the main checkout. Name
  the paths.
- Each worktree needs its own dependency install (`npm ci`, Maven cache is
  shared). The e2e compose project is named `scarcity-e2e` with a fixed default
  port, so two worktrees can't run the acceptance tests at the same time.
  Set `E2E_PORT` and don't do it.
- `.secrets/` is gitignored, so it isn't in a worktree. Module agents never need it.

Agents never push, and never commit anywhere but their own worktree branch.
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
  Service Dockerfiles build from the repo root with `-pl <module> -am`, and
  Maven needs **every** module listed in the root pom to be present, so adding
  a Java module means adding a `COPY <module>/pom.xml` line to every service
  Dockerfile (the repo agent does this when it merges the module).
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
  "id": "area:7f3c…",          // resource id, globally unique, type-prefixed
  "type": "area",
  "version": 41,               // integer, +1 on every change (see below)
  "children": ["entity:…"],    // contained resources, by id
  "state": { … }               // type-specific
}
```

#### Glossary (decided 2026-11-09)
| type | contains | what it is | example |
|---|---|---|---|
| `game` | areas | one run or world | dungeon 123 |
| `area` | entities | one connected space with geometry | citadel 456 |
| `entity` | items | something that occupies a position in an area. Its `kind` is `mobile` (moves by itself), `fixed` (doesn't move) or `player` (a character) | orc 789 |
| `item` | (items, later) | carried or lying around | short sword 012 |

So `game:123 ⊃ area:456 ⊃ entity:789 ⊃ item:012`. Use these words in code, APIs
and docs. Don't use "zone", "map", "mob" or "level" for them.

Accounts: a **user** is a login account (the `auth` service). A **character** is
a `player` entity that a user plays. Permadeath kills the character, never the
user.

#### Versions detect missed updates (decided 2026-11-10)
- Every resource has an integer `version`, incremented by one on every change,
  in the same transaction as the write
  (`UPDATE … SET version = version + 1 RETURNING version`; the row lock keeps
  the sequence gapless). A resource's version covers its own state **and its
  list of children**: adding or removing a child bumps the parent, and a child's
  state change bumps only the child.
- There is **no hash**. Clients never hash or canonicalise anything, so float
  formatting can't cause mismatches.
- The client compares a notification's version with its cached one:
  - cached + 1: apply the change;
  - higher: it missed something, so it `GET`s the resource again;
  - equal or lower: ignore it (duplicate).
- A missed *last* update gives no later message to reveal the gap, so there are
  two more checks:
  1. On subscribe (and so on every reconnect) the server sends a digest of the
     current versions of the area and everything in it (revised 2026-11-11: the
     server's map, not the client's; see the WebSocket protocol below).
  2. Every ~30 s the server sends each socket the same digest for each of its
     subscribed areas. Any entry higher than the cached version triggers a `GET`.
- REST `GET` returns the resource with its `version` and uses it as an `ETag`
  (`If-None-Match` gets a 304).

#### Generic resource API (decided 2026-11-10)
Three operations, defined in `libs/resource-model` so any service can implement
them. `world` is the only implementation for now, under `/api/world`. They let a
client list games, open one, and load area → entity → item layer by layer.
1. **List by filter:** `GET /resources?type=game&…`, paged.
2. **List children:** `GET /resources/{id}/children?type=area`, paged.
3. **Get one:** `GET /resources/{id}`.

#### Change notifications over Postgres `LISTEN/NOTIFY` (decided 2026-11-09, revised 2026-11-10)
- A service publishes in the **same transaction** as the write, so Postgres only
  delivers it if the transaction commits. Clients never see a rolled-back change.
- **One notification per area per tick**, not one per change. It carries a batch:
  `{"area","changes":[{"id","type","version","patch"}],"traceparent"}`. `patch`
  is the new state or a delta when that is cheap, so a client at version N that
  receives N+1 applies it without a `GET`. `GET`s happen only on a version gap.
  **For now a `patch` is always the full new `state` and replaces it** (world
  does this; deltas are a later optimisation and will be marked as such).
  - NOTIFY allows 8KB, about 50 changes. A bigger batch is split into several
    notifications, or sends only `id`/`type`/`version`, and clients `GET`.
  - `traceparent` is the W3C trace context of the transaction (absent if none).
    NOTIFY has no headers, so subscribers continue the trace from this field.
- The `subscriptions` service holds the client WebSockets and `LISTEN`s. A client
  subscribes to an **area** (the area and everything in it), not to each entity.
  The service keeps a map of area id → sockets and sends each socket one batch
  per tick.
- NOTIFY isn't durable, and that's accepted: a lost notification is caught by the
  version checks above.
- Services publish and subscribe only through an interface in
  `libs/resource-model`, never by calling `pg_notify`. That way we can move to
  Redis Streams later (many WebSocket replicas, high fan-out, replay after
  disconnect) without touching every service.
- We considered RabbitMQ and NATS and rejected them for now: stateful
  infrastructure we don't need yet.
- WebSocket routes need `timeouts: {request: 0s}` on their HTTPRoute rule,
  because Envoy's default 15s timeout kills them. Browsers can't set headers on
  a WebSocket, so the client sends its JWT in the first message.

#### WebSocket protocol (decided 2026-11-11; contract between `subscriptions` and clients)
`wss://scarcity.pessimistic-it.com/api/subscriptions/ws`, JSON text frames, one
object per frame, each with a `type`. Unknown fields are ignored on both sides.
- Client → server:
  - `{"type":"auth","token":"<jwt>"}` must be the first frame, within 5 s.
  - `{"type":"subscribe","area":"area:…"}` / `{"type":"unsubscribe","area":"…"}`.
  - `{"type":"ping"}`.
- Server → client:
  - `{"type":"ready","user":"<sub>","username":"…"}` after a valid `auth`.
  - `{"type":"subscribed","area":"…"}`, immediately followed by a `digest`.
  - `{"type":"digest","area":"…","versions":{"<id>":<version>,…}}`: the
    server's current versions of the area and everything in it. Sent on
    subscribe and about every 30 s. The client applies the version rule to each
    entry (higher than cached → `GET`). This replaces the earlier idea of the
    client sending its own version map: the server is the source of truth.
  - `{"type":"changes","area":"…","changes":[{"id","type","version","patch"}],"traceparent":"…"}`:
    one per tick, the batch from `LISTEN/NOTIFY` as is.
  - `{"type":"unsubscribed","area":"…"}`, `{"type":"pong"}`,
    `{"type":"error","message":"…"}` (the socket stays open).
- Close codes: `4401` auth missing, invalid, late or expired (the server closes
  when the JWT's `exp` passes); `4400` malformed frame before `auth`.
- Edge cases as implemented by `subscriptions` (2026-11-11): an `auth` with a
  missing or empty token closes `4401`; after `auth`, a non-JSON frame gets
  `error "malformed frame"`, a missing/unknown `type` or a second `auth` gets
  `error "unknown type"`, a non-`area:` id gets `error "invalid area id"`;
  `unsubscribe` always answers `unsubscribed`; subscribing twice resends
  `subscribed` + `digest`; a `world` 404 gives `error "unknown area"`, a `world`
  401 closes `4401`, other `world` failures give `error "could not load area: …"`;
  binary frames close `1003`, frames over 64KB close `1009`; any `Origin` is
  accepted (no cookies are involved).
- The server learns an area's versions by calling `world`'s resource API with
  the **subscribing user's JWT** on the first subscribe to that area (area +
  all pages of children), then keeps the map current from the notifications.
  It never reads `world`'s tables.

#### Ticks and scaling (decided 2026-11-10)
- Actions in an area are applied by a **tick loop** (a scheduled loop with a
  sleep is enough for now). A tick is one transaction: all its changes commit
  together and are published as one batch. Clients never see half a tick.
- **The area is the unit of ownership.** One `world` instance owns an area at a
  time and applies its actions one at a time: deterministic order and no locking
  inside an area. *Not built yet:* spreading areas across instances (a lease row
  in Postgres), and unloading an area when its last subscriber leaves (catch up
  when someone returns).
- Fan-out cost grows as acting characters × watchers (40 acting + 100 watching
  at 1 action/s is ~5,600 messages/s, which one `subscriptions` instance
  handles). It is the follow-up `GET`s that hurt, hence the patches in the
  notifications, reads served from an in-memory copy keyed by id and version, and
  `ETag`s. *Later:* interest management, i.e. filtering each batch per client by
  distance or line of sight, which also gives fog of war.
- How time works in the game (action durations, how mobs choose actions) is
  undecided; to be designed after the test area.

#### Test area (the next milestone)
A game containing one area, a small room (10×8) with a `fixed` entity (a pillar)
and a `mobile` entity slowly orbiting it, driven by the server: the tick loop
writes the new float position every second. Build order: `libs/resource-model`
→ `services/world` → `services/subscriptions` → client (WebSocket, resource
cache, UI to list games and show area, entities and their raw state).

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
