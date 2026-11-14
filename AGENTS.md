# AGENTS.md: working in the Scarcity monorepo

This file is for every agent (and human) that touches this repo. It holds the
rules. The contracts and the reasons behind them live in `docs/`, so read only
what your task needs.

## What to read
1. This file, then the `AGENTS.md` of the module you work in.
2. [`docs/resources.md`](docs/resources.md) if your module reads, writes or
   subscribes to game state (`libs/resource-model`, `services/world`,
   `services/subscriptions`, the clients).
3. [`docs/actions.md`](docs/actions.md) if it changes game state or shows
   what resources are doing, and [`docs/log.md`](docs/log.md) if it writes
   game state or shows history (`services/world`, the clients).
4. [`docs/websocket.md`](docs/websocket.md) if it serves or consumes the
   subscription WebSocket (`services/subscriptions`, the clients).
5. [`k8s-resources/README.md`](k8s-resources/README.md) if it deploys.
6. [`docs/decisions.md`](docs/decisions.md) only when you want to know *why*
   a rule exists or intend to change one. It isn't needed to do a task.

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

Game time passes only when someone acts: an area advances when a controller
(a player, or a service such as `orbital` that plays the test area) sends an
intent, and stands still otherwise (`docs/actions.md`). Keep the design open
for several players in a shared world (see the end of `docs/decisions.md`).

## Agent roles

```
human ⇄ repo agent (this file)        discusses features, splits work across modules,
          │                           owns cross-module contracts, CI, k8s-resources/
          ├─ module lead: auth         owns services/auth; reads services/auth/AGENTS.md
          │     ├─ local dev worker    implements one small step at a time
          │     ├─ local review worker reads the result against the criteria
          │     └─ (fallback) Sonnet developer + tester agents
          ├─ module lead: client-web   (one module lead runs at a time)
          └─ k8s agent (separate session: k8s-resources-b0)
                                       namespaces, domains, secrets, backups on the cluster
```

- **Repo agent** talks to the human. It owns the root docs, `docs/`,
  `modules.yaml`, `.github/`, `k8s-resources/` and any contract that crosses
  modules (HTTP APIs, resource schemas, JWT claims). It gives each module lead
  tasks.
- **Module lead** (`.claude/agents/module-lead.md`) oversees one module. It
  splits each task into small steps and has **local workers** do them: Claude
  Code on the LAN inference box (a small local model), started through herdr with
  `hc run … local`. One worker develops, a fresh one reviews. The workers are
  free but weak, so **the lead is the gate**: it runs every build and test
  itself, reads every diff and exercises the result. A worker's claim is never
  evidence.
- **Fallback.** The lead always splits a task into steps and assigns every
  step to a local worker first. A step leaves that plan only when the box is
  down or the step has failed twice locally; then the lead spawns a Sonnet **developer**
  (`module-developer.md`) and then a **tester** (`module-tester.md`). The
  tester gets only the task description and the diff, so it judges the result
  independently. If the runtime does not let the lead spawn agents, it does
  both phases itself, in order, and keeps them separate.
- **One module at a time.** The repo agent runs one module lead at once and
  merges its branch before starting the next. The inference box is shared by
  everything on the LAN and a worker needs its full context window.
- **Repo agent as developer.** Design-heavy or cross-module work that the
  human agrees doesn't suit a module lead (first: the world split, #103,
  tickets #106-#112) is done by the repo agent itself. Same rules as a lead:
  its own worktree and branch per ticket, the module's tests run on the final
  commit, a PR the human reviews, nothing merged on a red CI.
- **The k8s agent** lives in its own session (the `k8s-resources` repo). Cluster
  resources outside this namespace (domains, Gateway listeners, certs, secrets,
  storage classes, backup slots) are requested from it. Do not hand-edit cluster
  config.

**Models.** Every agent role pins `model: sonnet` in its frontmatter
(`.claude/agents/`). Don't pass a different `model` when spawning one unless the
human asks. Local workers are not agent roles: they are separate `claude-local`
processes, and only `claude-local` sets the endpoint variables. Never set
`ANTHROPIC_BASE_URL` in a session, a settings file or this repo.

**Who runs what.** Each layer produces one kind of evidence and doesn't
repeat another's:

| who | produces | doesn't |
|---|---|---|
| local worker | the code for one step | run anything |
| module lead | the affected test classes after each step; the full module suite **once, on the final commit** (SHA in the report) | run the full suite per step |
| tester (fallback) | the running thing exercised against the criteria, from outside | rerun the suite |
| repo agent | the diff review; checks only it can make (data shaped like the deployed database, effects on other modules) | rerun the module's suite, unless a rebase brought in changes to the module or its dependencies |
| CI | every changed module's tests on each push to `main`; a failure stops the deploy | |

CI is the backstop for a wrong report: nothing deploys on a lead's word alone.

**Usage.** Tool output, not reasoning, is most of an agent task's cost. Every
role keeps output small (`mvn -B -q` piped through `grep`/`tail`, `--tail` on
logs, no `cat` of big files, summary lines in reports; the agent definitions
have the exact commands). A lead stops after **two failed verification
rounds** and reports instead of looping; the repo agent decides what happens
next.

**Module leads stay inside their module directory.** When a task needs a change
elsewhere (another module, `k8s-resources/`, CI, `docs/`), they say so in their
report and don't make the change. The repo agent makes those changes or
delegates them.

**Follow-ups go in GitHub issues.** When you defer something (a known limitation,
a non-blocking bug, a decision for later), list it in your report. The repo
agent files it as an issue in scarcity-game/scarcity with `area: <module>` and
`priority: low|normal` labels. Before starting work, check the open issues for
your module (`gh issue list --repo scarcity-game/scarcity --label "area: <module>"`).
A report's "open issues" section must not be the only place a follow-up is
recorded.

### The board
Small, well-specified work runs off the
[Scarcity board](https://github.com/orgs/scarcity-game/projects/1) with no
module lead (`tools/kanban/README.md` has the commands and the ticket format):
- A **ticket** is an issue with the `agent: local` label, one module, a
  checklist of acceptance criteria and the files to read. The repo agent or
  the human writes it and moves it to **Todo**.
- The **runner** (`tools/kanban/kanban.py`) starts one local-model agent on
  the ticket in the runner's own checkout (not a worktree), on branch
  `ticket/<n>`, at the repo root. The agent may read and write anything,
  make temp files, install and add dependencies, and run the module's tests
  itself. It cannot commit, push, switch branches or use `gh`.
- **The runner is the gate, not the agent**: it commits only the changes
  under the module, throws away everything else, and runs the module's test
  command on what it committed. Only then does it push the branch, open a
  pull request (which flags changed dependency files) and move the ticket to
  **Done**. It resets and cleans the checkout before and after each ticket.
  This is the one place a branch is pushed by something other than the repo
  agent; `main` is never pushed by the runner.
- The repo agent or the human reviews the pull request (same review as for a
  lead's branch; `kanban.py review` has a Sonnet reviewer do a first pass and
  post its verdict) and **accepts** it (merge when CI is green; the ticket moves
  to **Accepted**) or **rejects** it with a concrete review (back to Todo).
- A ticket that fails or is rejected **twice** goes to **Blocked**: a module
  lead or the human takes it from there.
- Work that needs a design decision, crosses modules or touches auth or
  secrets is not a ticket; it goes through a module lead as before.

### Worktrees
Every unit of module work runs in its **own new git worktree**, never in the
main checkout.
- The repo agent spawns each module lead with `isolation: "worktree"`. The
  lead's workers (and fallback developer and tester) work in that same
  worktree, one after the other.
  A new task gets a new worktree, not a reused one.
- A module lead never touches the main checkout or another worktree. If it finds
  itself in the main checkout (`git rev-parse --git-dir` is a plain `.git`
  rather than `…/worktrees/…`), it stops and reports.
- A lead may commit to **its own worktree branch**, but never pushes, never
  touches `main` and never merges. It reports the branch name and the worktree
  path.
- The repo agent reviews the branch's diff (it stays inside the module plus
  agreed contract files, matches the contract, removes or weakens no test),
  checks that the SHA the lead's full suite ran on is the branch head, then
  merges it into `main` with `git merge --ff-only` and pushes. It does not
  run the module's suite again, unless a rebase brought in changes to the
  module or to a module it depends on (see "Who runs what").
  It removes the worktree and branch afterwards. A branch that fails its tester
  isn't merged.
- Never commit with `git commit -a` or `git add -A` in the main checkout. Name
  the paths.
- Each worktree needs its own dependency install (`npm ci`; the Maven cache is
  shared). The e2e compose project is named `scarcity-e2e` with a fixed default
  port, so two worktrees can't run the acceptance tests at the same time
  unless the second one sets `E2E_PORT` (see `clients/web-acceptance-tests/README.md`).
- `.secrets/` is gitignored, so it isn't in a worktree. Module agents never
  need it.

Agents never push, and never commit anywhere but their own worktree branch.
They never commit secrets. The repo is **public**.

## Architecture

### Modules
A module is one directory with its own `AGENTS.md` and `README.md`:

| kind | lives in | examples |
|---|---|---|
| `service` | `services/<name>` | auth, world, subscriptions, orbital |
| `library` | `libs/<name>` | resource-model, world-model, world-engine, world-db, world-seed |
| `content` | `content/<name>` | music, tilesets, item tables |
| `client` | `clients/<name>` | web (React), desktop (Electron, later) |

Every module is listed in [`modules.yaml`](modules.yaml). CI only builds what is
listed there.

The game rules are split across modules (planned in #103; dependencies point
one way, and the engine never touches the database):

| module | holds | depends on |
|---|---|---|
| `libs/world-model` | typed resource objects, the `Action`/`Effect` interfaces and catalogue, draws. Pure Java, Jackson only | none |
| `libs/world-engine` | the advance: events, effects, log entries in memory. Pure | world-model |
| `libs/world-db` | loading and committing an area, the area log tables, the lock, publishing | world-model, resource-model |
| `libs/world-seed` | the test area definition, seeding and upgrades | world-model, world-db |
| `services/world` | HTTP (resources, log, intents), security, wiring | all of the above |
| `services/orbital` | plays the test area's orbiter through the intents API | world-model |

Rules are objects, not branches. Every action, effect, behaviour kind and
controller kind is one implementation of an interface in `libs/world-model`,
found by name in the catalogue. The engine runs events through those
interfaces and never switches on a type name, so a new action is one new
class and no `if` anywhere else. The lint limits (`config/lint/`) keep the
methods small.

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
  in `Authorization: Bearer …`. A service acting on its own (such as
  `orbital`) authenticates with an API key (below).
- Every HTTP service listens on **8080**. Spring services serve actuator
  (health, `/actuator/prometheus`) on a separate management port **8081**,
  named `metrics` in the Deployment, which is never in a Service or route.
  Other services serve `/health` on 8080.
- Public paths are prefixed by the service: `/api/<service>/…`. The Gateway
  routes by longest prefix, and `/` goes to the web client. A WebSocket route
  needs `timeouts: {request: 0s}` on its HTTPRoute rule.

### Auth and JWTs
- `auth` mints RS256 JWTs. Claims: `iss=scarcity-auth`, `sub=<user uuid>`,
  `username`, `iat`, `exp`.
- Public keys are published at `GET /api/auth/.well-known/jwks.json`
  (in-cluster: `http://auth:8080/api/auth/.well-known/jwks.json`). Other
  services verify tokens locally against the JWKS (Spring:
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`).
- **Service principals** authenticate to `world` with `X-Api-Key: <key>`
  instead of a JWT. `world` maps each key to a principal name, configured
  from the k8s secret `world-api-keys` (one entry per principal). A
  principal may read like a user and may post intents only for entities
  whose `controller` names it (`docs/actions.md`, "Controllers"). A request
  with both a key and a JWT is a 400. Keys are never logged, not even
  hashed. JWTs stay the only credential for users.

### Resources (contract: `docs/resources.md`)
Everything in the game context is a **resource**: `game ⊃ area ⊃ entity ⊃ item`,
each `{id, type, version, children, state}` with a type-prefixed id such as
`area:7f3c…`. Use exactly those four words; never "zone", "map", "mob" or
"level". The rules every module must respect:
- `version` is an integer, +1 per change in the writing transaction. No hashes.
- Reads go through the generic resource API (`/api/world/resources`), with the
  version as `ETag`.
- Changes are published over Postgres `LISTEN/NOTIFY`, one batch per area per
  advance, **only through `libs/resource-model`**; services never call `pg_notify`
  or `LISTEN` themselves. Clients receive them from `subscriptions` over the
  WebSocket (`docs/websocket.md`) and apply the version rule.
- An advance is one transaction; the area is the unit of ownership.

### Observability
- **OpenTelemetry** everywhere: export over OTLP, propagate W3C `traceparent`.
  A request should produce one trace from the browser to the database.
- The collector endpoint comes from the k8s agent; don't hard-code it.
- Java services use Spring Boot's Micrometer Tracing with the OTel bridge, not
  the OTel Java agent. Logs include the trace and span ids. Metrics are served
  at `/actuator/prometheus`.
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
- A cross-module decision gets a dated entry in `docs/decisions.md` and, if it
  changes a contract, an update to the matching `docs/` file.
- **Java lint is part of the build** (`verify`): Checkstyle and PMD with
  complexity limits (cyclomatic 10, method length 60, nesting depth 2, and
  more; `config/lint/README.md`). A method over a limit gets split, not
  suppressed. The baseline files in `config/lint/` hold the violations of
  2026-11-13. Entries may be deleted as code is fixed, but **never added**
  without the human's OK, and new modules get none.
