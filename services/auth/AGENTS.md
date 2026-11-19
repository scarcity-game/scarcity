# services/auth: module agent notes

**Kind:** service · **Stack:** Java 25, Spring Boot 3.5, Maven (child of root `pom.xml`)
· **Ports:** 8080 (API), 8081 (actuator) · **k8s:** `k8s-resources/auth/` · **Image:** `ghcr.io/scarcity-game/scarcity-auth`

## Purpose
Owns user accounts and identity. Stores users with bcrypt password hashes and
mints RS256 JWTs. Publishes a JWKS so every other service can verify tokens
locally.

## Contract (other modules depend on this; change it only through the root agent)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/auth/register` | `{"username","password"}` | `201 {"id","username","createdAt"}`; `409` if the username is taken; `400` if validation fails |
| POST | `/api/auth/login` | `{"username","password"}` | `200 {"token","tokenType":"Bearer","expiresAt"}`; `401` on bad credentials (same response for unknown user and wrong password) |
| GET | `/api/auth/me` | `Authorization: Bearer <jwt>` | `200 {"id","username","createdAt"}`; `401` without a valid token |
| POST | `/api/auth/service-token` | `X-Api-Key: <key>`, no body | `200 {"token","expiresIn":900}`; `401` for a missing or unknown key (the same response) |
| GET | `/api/auth/.well-known/jwks.json` | | `200` JWKS with the public signing key(s) |
| GET | `:8081/actuator/health` (+ `/liveness`, `/readiness`) | | probes, on the **management port 8081** (not publicly routed) |
| GET | `:8081/actuator/prometheus` | | Prometheus text exposition, management port only |

Port 8080 serves only `/api/auth/**`; every `/actuator/**` path there is 404. Nothing
under `/api/auth` exposes actuator or metrics (tested).

Errors use RFC 7807 `application/problem+json`.

- Username: 3-32 chars, `[A-Za-z0-9_-]`, unique case-insensitively, stored as entered.
- Password: NFKC-normalised first (`PasswordNormalizer`, only `AuthService` calls it; login also
  accepts a legacy raw-hash match and re-hashes), then 8-128 code points. Hashed with bcrypt. bcrypt only reads the first 72
  bytes, so reject passwords longer than 72 UTF-8 bytes rather than silently
  truncating them.
- JWT: RS256, `kid` set. Claims are `iss=scarcity-auth`, `sub=<user uuid>`,
  `username`, `iat`, `exp` (1h).
- Service token (`principal/`): `iss`, `sub=<principal>`, `kind=service`,
  `iat`, `exp` (15 min, `auth.service-token.ttl`), no `username`; `/me`
  rejects it. Keys are read once at startup by `ServiceKeys` from
  `AUTH_SERVICE_KEYS_DIR` (one file per principal; SHA-256 digests only,
  compared in constant time). Audit lines `service-token issued for principal
  <p>` and `service-token refused: invalid_key`; never the key.

## Configuration
| env | meaning |
|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Postgres (in k8s: `postgres-service:5432/scarcity`, secret `postgres-secrets`) |
| `AUTH_SERVICE_KEYS_DIR` | Service principals' API keys, one file per principal (in k8s: secret `service-api-keys` as a volume). Unset means no keys: every exchange is a 401. |
| `AUTH_JWT_PRIVATE_KEY_PATH` | PKCS#8 PEM RSA private key (in k8s: `/etc/auth-jwt/private.pem` from secret `auth-jwt`). The public key is derived from it. If unset, an ephemeral key is generated and a warning is logged (dev/test only). |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | OTLP/HTTP traces endpoint. In prod: `http://alloy.observability.svc:4318/v1/traces` (set in the Deployment by the k8s agent). **Unset** = no exporter; spans are still made so logs carry trace ids. Must be unset, not empty: an empty string fails startup (`Invalid endpoint`). |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | Root sampling ratio, default `1.0` (Boot's own default is 0.1). Parent-based: an incoming `traceparent` with sampled=01 is always recorded. |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra OTel resource attributes, e.g. `k8s.namespace.name=scarcity`. Boot merges them in. Our explicit `service.name` property wins over them and over `OTEL_SERVICE_NAME`. |
| `HOSTNAME` | Pod name (set by k8s). Becomes ECS `service.node.name`; omitted when unset. |
| `SPRING_PROFILES_ACTIVE=k8s` | Also switches console logs to ECS JSON (`logging.structured.format.console=ecs`). |
| ports | `8080` application API (`server.port`), `8081` actuator (`management.server.port`; the container port named `metrics` in the Deployment). |
| JVM flags | In the Dockerfile `ENTRYPOINT` (`-XX:MaxRAMPercentage=75`, `--sun-misc-unsafe-memory-access=allow`), not `JAVA_TOOL_OPTIONS`, which prints a non-JSON line to stderr. |

## Data
Postgres schema `auth`, managed by Flyway (`src/main/resources/db/migration`).

## Local build note
Run from the repo root on JDK 25 (`mvn -v`; if Maven picks another JDK, set
`JAVA_HOME` as in the root `README.md`):

```bash
mvn -ntp -q -pl services/auth -am verify
```

Testcontainers talks to Docker Desktop directly; no socket mount or host override
is needed. CI runs the equivalent from `services/auth`:
`mvn -B -f ../../pom.xml -pl services/auth -am verify` with JDK 25.

## Conventions and gotchas
- Package `com.pessimisticit.scarcity.auth` (the groupId has a hyphen).
- No Spring Security starter: `spring-security-crypto` and `nimbus-jose-jwt`
  (pinned 9.37.4) directly. Data access is plain `JdbcClient`, no JPA.
- Login failures are one byte-identical 401, with exactly one bcrypt compare
  always run. Never add an enumeration oracle (responses, logs or spans).
- Never put a username, password, hash, API key or key digest in a log line, span or metric.
- bcrypt cost 12 (`auth.password.bcrypt-strength`); Bean Validation (`@Min(10) @Max(31)`) fails startup outside 10-31.
- Tracing, ECS logs and metrics come from `libs/observability` (imported
  `scarcity-observability.yml`, `scarcity.observability.service-name: auth`);
  the audit lines are auth's own. Outgoing HTTP clients must be built from the
  injected `RestClient.Builder`, never `RestClient.create()`.
- `@ConfigurationProperties` are checked with Bean Validation (`@Validated`),
  not hand-written checks.
- Management port 8081 exposes `health` and `prometheus` only.
- Tests: integration tests use `@AutoConfigureObservability`;
  `EcsLoggingIntegrationTest` has its own surefire execution (fresh JVM).
- The `Dockerfile` starts with the shared dependency stage; a new Java module
  changes it in `config/docker/` (see `/AGENTS.md`), never here by hand.
- Java lint (`config/lint/`) is part of `verify`; see `/AGENTS.md`.

## More docs (read only when your task needs them)
- [`docs/decisions.md`](docs/decisions.md): read when you want the reasons
  behind the initial build (validation rules, login, JWT and key details,
  timestamps, JWKS, actuator, Dockerfile) or intend to change one.
- [`docs/observability.md`](docs/observability.md): read when you touch
  tracing, logs, metrics, audit lines or span attributes (the history; the
  code is in `libs/observability` now).

Cross-module rules (JWT claims, API keys, ports, observability) are in
[`/AGENTS.md`](../../AGENTS.md); the JWKS and JWT contract above is what
other services rely on.
