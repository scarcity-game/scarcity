# services/auth: module agent notes

**Kind:** service · **Stack:** Java 25, Spring Boot 3.5, Maven (child of root `pom.xml`)
· **Ports:** 8080 (API), 8081 (actuator) · **k8s:** `k8s-resources/auth/` · **Image:** `ghcr.io/scarcity-game/scarcity-auth`

## Purpose
Owns user accounts and identity. Stores users with bcrypt password hashes and
mints RS256 JWTs. Publishes a JWKS so every other service can verify tokens
locally.

## Contract (other modules depend on this; change it only through the repo agent)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/auth/register` | `{"username","password"}` | `201 {"id","username","createdAt"}`; `409` if the username is taken; `400` if validation fails |
| POST | `/api/auth/login` | `{"username","password"}` | `200 {"token","tokenType":"Bearer","expiresAt"}`; `401` on bad credentials (same response for unknown user and wrong password) |
| GET | `/api/auth/me` | `Authorization: Bearer <jwt>` | `200 {"id","username","createdAt"}`; `401` without a valid token |
| GET | `/api/auth/.well-known/jwks.json` | | `200` JWKS with the public signing key(s) |
| GET | `:8081/actuator/health` (+ `/liveness`, `/readiness`) | | probes, on the **management port 8081** (not publicly routed) |
| GET | `:8081/actuator/prometheus` | | Prometheus text exposition, management port only |

Port 8080 serves only `/api/auth/**`; every `/actuator/**` path there is 404. Nothing
under `/api/auth` exposes actuator or metrics (tested).

Errors use RFC 7807 `application/problem+json`.

- Username: 3-32 chars, `[A-Za-z0-9_-]`, unique case-insensitively, stored as entered.
- Password: 8-128 chars. Hashed with bcrypt. bcrypt only reads the first 72
  bytes, so reject passwords longer than 72 UTF-8 bytes rather than silently
  truncating them.
- JWT: RS256, `kid` set. Claims are `iss=scarcity-auth`, `sub=<user uuid>`,
  `username`, `iat`, `exp` (1h).

## Configuration
| env | meaning |
|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Postgres (in k8s: `postgres-service:5432/scarcity`, secret `postgres-secrets`) |
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
JDK 25 (Temurin) is installed natively on the workstation. Run from the repo root,
setting `JAVA_HOME` explicitly because brew's `mvn` launcher may otherwise pick JDK 24:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 25)" mvn -B -pl services/auth -am verify
```

Testcontainers talks to Docker Desktop directly; no socket mount or host override
is needed. CI runs the equivalent from `services/auth`:
`mvn -B -f ../../pom.xml -pl services/auth -am verify` with JDK 25.

## Decision log
- **2026-11-09: users table, bcrypt, JWT minting (initial build).**
  - Package `com.pessimisticit.scarcity.auth`. The groupId has a hyphen, which Java packages can't contain.
  - **No Spring Security starter.** It uses `spring-security-crypto` (BCrypt) and `nimbus-jose-jwt` directly.
    - nimbus is pinned to 9.37.4 because Boot 3.5.7 doesn't manage it. That matches spring-security-oauth2-jose 6.5.x.
    - `/me` checks the Bearer token by hand against our own key. The scheme check is case-insensitive.
    - 401s send `WWW-Authenticate: Bearer`, plus `error="invalid_token"` when a token was sent.
  - **Data access** is plain `JdbcClient`; there is no JPA.
    - Flyway runs with `schemas=auth`, `default-schema=auth` and `create-schemas=true`. History lives in `auth.flyway_schema_history`.
    - `auth.users` has a unique index on `lower(username)` and a CHECK on the username format.
    - The application generates `id`.
  - **Duplicate usernames.** A pre-check avoids bcrypt work, but the unique index is the real guard: `DuplicateKeyException` becomes 409. A concurrent-registration test covers the race.
  - **bcrypt cost** is 12 by default (`auth.password.bcrypt-strength`). Startup fails if it is below 10. Tests use 10.
  - **Validation.** "Chars" means Unicode code points.
    - The 72-UTF-8-byte limit is enforced too, so in practice at most 72 ASCII characters.
    - Unpaired surrogates are rejected. Passwords are not NFC-normalised.
    - All violations come back together as `errors: [{field, message}]` on the 400 problem.
    - JSON input is strict: numbers and booleans are not converted to String (`web/JsonConfig`). Missing or null fields give 400.
  - **Login.** Missing fields give 400. Every other failure gives one byte-identical 401 "Invalid username or password": unknown user, wrong password, malformed username, password over 72 bytes.
    - Exactly one bcrypt compare always runs, against a dummy hash when the user doesn't exist.
    - The response sends `Cache-Control: no-store`.
  - **JWT.**
    - The `kid` is the RFC 7638 SHA-256 thumbprint; the header carries `typ=JWT`.
    - `iat` is in whole seconds and `exp = iat + 3600`. There is no `jti`, so logins in the same second return the same token.
    - Verification requires RS256, our kid, `iss`, every claim present and `sub` a UUID. It allows 30 s of clock skew.
    - `/me` returns 401 if the user no longer exists.
  - **Key.**
    - It must be PKCS#8 and RSA ≥ 2048 bits (3072 expected). PKCS#1 fails with a conversion hint.
    - A set but unreadable path makes startup fail. A blank or unset path generates an ephemeral RSA-3072 key and logs a WARN.
    - The env var is mapped explicitly: `auth.jwt.private-key-path: ${AUTH_JWT_PRIVATE_KEY_PATH:}`.
  - **Timestamps** are ISO-8601 UTC strings. `createdAt` has microsecond precision. `expiresAt` equals `exp`.
  - **JWKS** returns `application/json` with `Cache-Control: public, max-age=300` and one key. Rotation is not designed yet.
  - **No Location header on 201**, because the contract has no per-user URL.
  - **Errors.** `ApiExceptionHandler` makes 400/404/405/415/500 all problem+json. The whitelabel page is off.
  - **Actuator.** Only `health` is exposed, with probes on. The readiness group is `readinessState,db`; liveness doesn't depend on the DB. It runs on port 8080. *(Superseded 2026-11-09: port 8081, plus `prometheus`.)*
  - **`k8s` profile.** Graceful shutdown (20 s), a Hikari pool of at most 5, plain logs. *(Superseded 2026-11-09: ECS JSON logs.)*
  - **Dockerfile.** It copies the POMs, runs `dependency:go-offline`, copies `src`, then runs `package -pl services/auth -am`.
    - The runtime is `eclipse-temurin:25-jre` as uid/gid 10001, starting `java -jar /app/app.jar`.
    - When the root pom gains more modules, their POMs must be COPYed before go-offline.
    - The image also works with a read-only root filesystem plus a tmpfs on `/tmp`.
  - **Mockito** is loaded as a surefire `-javaagent` (JDK 21+ self-attach is deprecated).
  - **Known issue.** Flyway (Boot-managed) warns that PostgreSQL 18.6 is newer than it supports. It works; upgrade when Boot does.
- **2026-11-09: tracing, trace-correlated logs, Prometheus metrics (#18).**
  - **Layout, built to be copied.** All of it lives in three places, with no auth logic:
    the observability block of `pom.xml`, `src/main/resources/observability.yml`
    (imported via `spring.config.import`) and the package
    `com.pessimisticit.scarcity.auth.observability`. The next Java service copies those
    and changes only `scarcity.observability.service-name`.
  - **Stack.** Spring Boot 3.5.7's Micrometer Tracing + `micrometer-tracing-bridge-otel`,
    `opentelemetry-exporter-otlp` (OTLP/HTTP) and `micrometer-registry-prometheus`, all
    Boot-managed. No OTel Java agent.
  - **JDBC spans:** `net.ttddyy.observation:datasource-micrometer-spring-boot` pinned to
    **1.2.1** (`datasource-micrometer.version`). The 1.x line is Boot 3 (2.x needs Boot 4).
    1.2.1 is the newest 1.x built against a Boot 3.5 patch not newer than ours (3.5.6, and
    micrometer-tracing 1.5.4 vs our 1.5.5). 1.3+ is built against 3.5.10+; revisit when
    the root pom moves Boot forward.
    - `jdbc.includes: query` only. Connection spans largely duplicate Hikari's pool
      metrics (`hikaricp_connections_*`) and wrap every query in an extra level. Result-set
      spans add a tiny child per SELECT. With connection spans off, the DB health check
      (`Connection.isValid`) produces no span at all.
    - Spans carry the SQL text with `?` placeholders (`jdbc.query[0]`). Bind values are
      off (`include-parameter-values: false`, also the library default, set explicitly).
      Query and slow-query logging are off.
    - A predicate drops `jdbc.*` observations that have no enclosing observation, so Flyway
      migrations at startup don't produce one root trace per statement.
  - **service.name=auth** via `management.opentelemetry.resource-attributes[service.name]`.
    `spring.application.name` stays `scarcity-auth` (the artifact/image name, shown in
    the plain log pattern). Boot uses spring.application.name only as a fallback; an
    explicit resource attribute is OTel's own knob and wins over `OTEL_SERVICE_NAME`.
    The same value feeds the ECS `service.name` and the `application` metric tag.
  - **Propagation:** `management.tracing.propagation.type: w3c`. Boot's default produces
    W3C but also consumes B3; we accept W3C only (tested: B3 headers are ignored).
    Sampling: probability 1.0, parent-based.
  - **No endpoint, no exporter.** In Boot 3.5.7 `OtlpTracingConfigurations.ConnectionDetails`
    is `@ConditionalOnProperty("management.otlp.tracing.endpoint")` and the exporter
    beans are `@ConditionalOnBean(OtlpTracingConnectionDetails)`. With the property unset
    there is no `OtlpHttpSpanExporter`; the SDK's `BatchSpanProcessor` gets an empty
    exporter list, so spans are created (ids in logs) and dropped, with no network and no
    log noise. OTLP log export (`management.otlp.logging.endpoint`) works the same way and
    is not configured.
  - **Actuator is not traced.** On the separate management port actuator requests aren't
    observed at all (the management child context has no `ServerHttpObservationFilter`).
    An `ObservationPredicate` on the actuator base path also drops them in case actuator
    ever shares the server port. Both are tested.
  - **Logs.** Default profile: Boot's correlation pattern, `[<traceId>-<spanId>]` after
    the thread name (blank brackets outside a request). `k8s` profile: Boot's ECS JSON,
    one object per line. The trace and span ids are the top-level fields **`traceId`**
    and **`spanId`** (Micrometer's MDC keys, which Boot's ECS formatter copies verbatim,
    not ECS's `trace.id`/`span.id`). `service.name` is `auth`. Boot 3.5.7 also writes an
    empty `"service":{"node":{}}`.
  - **No secrets in telemetry.** Tests register and log in with a distinctive password,
    with DEBUG on for Spring MVC, Spring JDBC and datasource-proxy, and assert that
    neither the password nor its bcrypt hash appears in any span name, attribute, event or
    log line (plain and JSON). `Credentials.toString()` and `User.toString()` were
    already redacted.
  - **Management port 8081**, exposing `health` and `prometheus` only. Port 8080 returns
    404 for `/actuator/**`. Common metric tag `application=auth`. Metrics are Boot's
    defaults: JVM, process, Hikari, Tomcat, `http_server_requests_seconds` and
    `jdbc_query_seconds` (from datasource-micrometer). Actuator traffic doesn't appear in
    `http_server_requests`.
  - **Outgoing HTTP.** auth makes no outgoing calls yet. Any future client must be built
    from the injected `RestClient.Builder` (or `RestTemplateBuilder`/`WebClient.Builder`),
    never `RestClient.create()`/`new RestTemplate()`, so it gets Boot's observation
    instrumentation and sends `traceparent`.
  - **Dockerfile.** `EXPOSE 8080 8081`. The JVM flags are in the entrypoint, not in
    `JAVA_TOOL_OPTIONS` (the JVM echoes that as a plain "Picked up JAVA_TOOL_OPTIONS"
    line on stderr). `-XX:MaxRAMPercentage=75` sizes the heap from the container limit.
    `--sun-misc-unsafe-memory-access=allow` is there because the OTel SDK's shaded JCTools
    calls `sun.misc.Unsafe`, and JDK 25 would otherwise print a 4-line warning to stderr
    at startup. Both would break one-JSON-object-per-line.
  - **In k8s** (Deployment owned by the k8s agent): `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://alloy.observability.svc:4318/v1/traces`,
    `OTEL_RESOURCE_ATTRIBUTES` carries `k8s.namespace.name`, which Boot merges into the
    resource. Our explicit `service.name` wins over both it and `OTEL_SERVICE_NAME`.
    The container port named `metrics` is 8081.
  - **Collector outages are loud on purpose.** While the collector is unreachable, the
    OTLP exporter logs a throttled ERROR with a stack trace roughly every 13 s. We keep
    it, so an outage shows up in the logs.
  - **ECS `service.node.name`** = `${HOSTNAME:}` (the pod name). Boot writes `node.name`
    only when the value is non-empty, so with `HOSTNAME` unset the line has `"node":{}`.
  - **`spring.mvc.servlet.load-on-startup: 1`** (application.yml, not observability):
    DispatcherServlet starts at boot, so its init lines aren't logged inside the first
    request, which has no trace id at that point.
  - **Copying `observability.yml`:** don't leave `management.opentelemetry.resource-attributes`
    as an empty key. An empty map value fails property binding at startup; delete
    the key instead.
  - **Tests.** `@SpringBootTest` turns tracing and metrics export off unless
    `@AutoConfigureObservability` is present, so the integration tests use it. Spans are
    captured with an `InMemorySpanExporter` bean (`InMemorySpans`).
    `EcsLoggingIntegrationTest` runs in its own surefire execution (`ecs-logging-test`),
    so it gets a fresh JVM. Boot configures logback only once per JVM, so a k8s-profile
    context started after another cached context would still log plain text.
