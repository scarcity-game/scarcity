# auth: observability (tracing, logs, metrics, audit lines)

Moved verbatim from the former "Decision log" in `services/auth/AGENTS.md` (entries 2026-11-09, #18).

- **2026-11-14: moved to `libs/observability`.** The copies in auth, world,
  subscriptions and orbital were identical but for the package, so they are one
  auto-configured lib now: `observability.yml` is the lib's
  `scarcity-observability.yml`, the `observability` package is
  `com.pessimisticit.scarcity.observability`, and the pom block is one dependency
  (plus datasource-micrometer for a service with a database). The typed-attributes
  handler is split into a server and a client handler. The entries below describe
  the copied layout as it was built; the behaviour is unchanged, and the
  integration tests that prove it are still auth's.

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
- **2026-11-09: audit log lines and OTel semantic conventions on spans (#18 follow-up).**
  - **Audit lines.** `user/AuditLog` writes INFO lines on logger
    `com.pessimisticit.scarcity.auth.audit` through the SLF4J fluent API (`addKeyValue`).
    Fields: `event.action` (`register`|`login`), `event.outcome` (`success`|`failure`),
    `event.reason` (`username_taken`|`invalid_credentials`) and `user.id` (success only).
    Messages: `register succeeded for user <id>`, `login succeeded for user <id>`,
    `login failed: invalid_credentials`, `register failed: username_taken`.
    - Every login 401 cause (unknown user, wrong password, malformed username, password
      over 72 bytes) gives the same line: no enumeration oracle. Failure lines carry no
      username, password, hash or user id. Both register-conflict paths (pre-check and
      `DuplicateKeyException`) are audited.
    - Validation 400s (missing fields, bad format) are not audited.
    - Boot 3.5.7's ECS formatter emits key-values and **nests dotted keys**
      (`"event":{"action":..}`). `traceId`/`spanId` come from the MDC and stay top-level.
      The plain-text pattern prints `%m` only, so key-values do not show there; the
      messages are meaningful on their own.
    - Test: `EcsLoggingIntegrationTest` sends 7 requests with known `traceparent`s and
      asserts top-level `traceId`/`spanId` (equal to the server span), no `trace` object,
      the fields, identical failure lines, and no username/password. `AuthServiceAuditTest`
      covers the duplicate-key race path.
  - **Semconv span attributes.** Spring Framework 6.2.12 ships only `Default*` conventions;
    no OTel one (that is Framework 7). So `observability/Semconv{Server,Client}RequestObservationConvention`
    extend the defaults and are picked up by Boot as beans.
    - Semconv keys are **high-cardinality** key-values, so they reach spans only. Metrics
      keep exact tag sets (test-enforced): `http_server_requests_seconds`:
      `application,error,exception,method,outcome,status,uri`; client adds `client_name`;
      `jdbc_query_seconds`: `application,error,jdbc_datasource_driver,jdbc_datasource_name,jdbc_datasource_pool`.
      None of 17 semconv names appear in `/actuator/prometheus`.
    - Server span: `http.request.method`, `http.response.status_code`, `url.path`,
      `url.scheme`, `http.route` (not on 404, where Spring reports `/**`), `error.type`
      (exception class, or status for 5xx), `server.address/port`, `network.protocol.version`,
      `user_agent.original` (max 256 chars). Never `url.query`, `client.address` or `http.url`.
      The legacy Micrometer low-cardinality keys (`method`, `status`, `uri`, `outcome`) still
      appear on spans too. `server.address` is the client-supplied Host header.
    - **Span names** are now `"{METHOD} {route}"` (just `{METHOD}` with no route); they were
      `http post /uri`. Update any dashboard that filtered on the old names.
    - **Int attributes.** Micrometer key-values are strings, so `SemconvTypedSpanAttributesHandler`
      sets `http.response.status_code` and `server.port` as longs in `onStop` (before the tracing
      handler ends the span). TraceQL `{span.http.response.status_code=401}` therefore matches.
    - **Client** (RestClient/RestTemplate built from the injected builder): `http.request.method`,
      `http.response.status_code`, `url.full` (no query or userinfo), `server.address/port`,
      `error.type`.
    - **JDBC**: `SemconvQueryObservationConvention` adds `db.system` and `db.system.name`
      (from the JDBC URL via Boot's `JdbcConnectionDetails`) and `db.query.text` (placeholders
      only, same as `jdbc.query[0]`). A service without JDBC must drop that bean and convention.
    - When moving to Framework 7, its shipped OTel conventions can replace these classes;
      re-check metric tags then.
