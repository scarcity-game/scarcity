# AGENTS.md: libs/observability

Tracing, metrics and the shared observability configuration for every Java
service, as Spring Boot auto-configuration (package
`com.pessimisticit.scarcity.observability`). A service depends on it and
imports its configuration; it never copies any of it. Lint applies with no
baseline entries. The reasons and the full attribute lists are in
`services/auth/docs/observability.md` (it was built in auth for #18).

## Using it
```xml
<dependency>
  <groupId>com.pessimistic-it.scarcity</groupId>
  <artifactId>observability</artifactId>
</dependency>
<!-- only with a database: JDBC query spans -->
<dependency>
  <groupId>net.ttddyy.observation</groupId>
  <artifactId>datasource-micrometer-spring-boot</artifactId>
</dependency>
```
```yaml
spring:
  config:
    import: classpath:scarcity-observability.yml
scarcity:
  observability:
    service-name: world   # OTel service.name, ECS service.name, `application` metric tag
```
The lib brings actuator, the OTel bridge, the OTLP exporter and the
Prometheus registry. Versions: Boot's, and `datasource-micrometer.version`
(1.2.1) in the root pom. An imported file wins over the one importing it, so
`scarcity-observability.yml` sets no `service-name` default; a service that
needs another value for one of its keys sets the env var.

## What it configures (`ObservabilityAutoConfiguration`)
| applies | beans |
|---|---|
| always | `SemconvClientRequestObservationConvention` (OTel semconv on HTTP client spans), `SemconvClientSpanAttributesHandler` (integer `http.response.status_code`, `server.port`) |
| servlet web apps (`Server`) | `SemconvServerRequestObservationConvention`, `SemconvServerSpanAttributesHandler`, `noActuatorServerObservations` |
| datasource-micrometer present (`Jdbc`) | `SemconvQueryObservationConvention` (`db.system`, `db.query.text`), `jdbcOnlyInsideAnotherObservation` |

Each convention and handler backs off (`@ConditionalOnMissingBean`) when the
service defines its own. Semconv keys are high-cardinality key-values, so
they reach spans only and metric tags don't change.

`scarcity-observability.yml`: management port 8081, `health,prometheus`
exposed, sampling 1.0, W3C propagation only, no OTLP endpoint unless
`MANAGEMENT_OTLP_TRACING_ENDPOINT` is set, the `application` metric tag, the
datasource-micrometer settings (query spans only, no bind values), and ECS
JSON logs in the `k8s` profile.

Outgoing HTTP clients are observed only when built from Boot's injected
`RestClient.Builder` (never `RestClient.create()`).

## Tests
`mvn -B -f ../../pom.xml -pl libs/observability -am verify` (no Docker).
`ObservabilityAutoConfigurationTest`: which beans apply in a servlet app, a
non-web app without JDBC, and that a service's own convention wins.
`ObservabilityConfigTest`: the actuator and JDBC predicates. The end-to-end
proof (span attributes, metric tag sets, ECS lines) is auth's
`ObservabilityIntegrationTest` and `EcsLoggingIntegrationTest`.
