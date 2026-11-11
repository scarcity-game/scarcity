# Scarcity

An online, turn-based, permadeath roguelike in the tradition of
[Dungeon Crawl Stone Soup](https://github.com/crawl/crawl). It has procedurally
generated levels, items and structure, and every action costs time.

Live at **https://scarcity.pessimistic-it.com**.

## How it differs from DCSS

| | DCSS | Scarcity |
|---|---|---|
| Numbers | Integers, grid squares | Floats for most values: positions, distances, stats, HP, probabilities |
| Play | Offline or online | **Online only.** Clients (browser or desktop) need a connection to the central server |
| Information | Terse ("1-5 damage") | **Information dense.** Detailed play logs, and the exact distributions behind every roll ("Gaussian, μ=4.2, truncated to [1, 5)") |

What it shares with DCSS: procedural generation, turns, permadeath, and actions
that take varying amounts of time.

### Future ideas
- **Wall-clock turns.** In-game time is pinned to real time. If you haven't picked an action when your turn comes, you lose it.
- **Shared worlds.** Multiple players in one world.

## Repository layout

This is a monorepo. Each top-level group holds **modules**. A module is a library, a content pack, a service or a client.

```
services/      independently deployed microservices (Java 25 / Spring Boot 3 by default)
  auth/        users, bcrypt passwords, JWT minting + JWKS
libs/          shared libraries (e.g. map generation, the resource model)
content/       content modules (e.g. music, tilesets, item tables)
clients/       game clients
  web/         React + Vite browser client
k8s-resources/ Kubernetes manifests for everything deployed (namespace `scarcity`)
modules.yaml   module registry: CI builds, tests, and deploys what is listed here
AGENTS.md      how humans and agents work in this repo; start here
```

## Architecture in one paragraph

Services are microservices that share the `scarcity` Kubernetes namespace.
They talk to each other over plain HTTP. When a user starts an action, each
call carries that user's JWT. `auth` mints the JWTs (RS256) and publishes a
JWKS so other services can verify them without calling back. Every game object
is a **resource**: it has a resource id, a type and a version, and it may
contain other resources (game → area → entity → item). Clients subscribe to an
area over WebSockets. Each change carries the resource's new version; a gap in
versions tells the client it missed something and should do a REST refresh.
Persistence is Postgres, backed up nightly. See
[AGENTS.md](AGENTS.md) for the details.

## Local development

Prerequisites: JDK 25, Maven 3.9+, Node 24, Docker (for Testcontainers + local Postgres).

**macOS + Docker Desktop:** point Testcontainers at the socket directly.
Otherwise it probes Docker Desktop's app container and macOS shows
"iTerm would like to access data from other apps" on every test run.

```bash
echo "docker.host=unix://$HOME/.docker/run/docker.sock" >> ~/.testcontainers.properties
```

If brew's `mvn` picks an older JDK, set `JAVA_HOME="$(/usr/libexec/java_home -v 25)"`.

```bash
# all Java modules
mvn -B verify

# a single service
mvn -B -pl services/auth -am spring-boot:run

# web client (proxies /api to localhost:8080)
cd clients/web
npm ci
npm run dev
```

## Deployment

Every push to `main` runs [.github/workflows/ci.yaml](.github/workflows/ci.yaml).
It builds and tests every module in `modules.yaml`, pushes images to
`ghcr.io/scarcity-game/scarcity-<module>`, and applies `k8s-resources/` with
kustomize. Pull requests run the build and tests only. See
[k8s-resources/README.md](k8s-resources/README.md) for how a module registers a
deployment.
