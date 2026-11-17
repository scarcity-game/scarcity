# observability

Tracing (Micrometer Tracing with the OpenTelemetry bridge, OTLP), Prometheus
metrics, OTel semantic-convention span attributes and trace-correlated ECS
logs for every Java service, as Spring Boot auto-configuration. Add the
dependency, import `classpath:scarcity-observability.yml` and set
`scarcity.observability.service-name`; see [AGENTS.md](AGENTS.md).

    mvn -B -f ../../pom.xml -pl libs/observability -am verify
