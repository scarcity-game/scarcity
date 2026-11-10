package com.pessimisticit.scarcity.auth.observability;

import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.awaitSpans;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.flush;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.randomSpanId;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.randomTraceId;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.traceparent;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.auth.TestcontainersConfiguration;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Tracing, log correlation, metrics and the management port, against a real Postgres.
 * Debug logging is switched on for Spring MVC, Spring JDBC and datasource-proxy so the
 * secret-leak test also covers what an operator would see after turning on DEBUG.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.password.bcrypt-strength=10",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.jdbc=DEBUG",
        "logging.level.net.ttddyy=DEBUG",
})
@Import({ TestcontainersConfiguration.class, InMemorySpans.class })
@ExtendWith(OutputCaptureExtension.class)
// Boot test contexts switch tracing and metrics export off unless asked; run them as in prod.
@AutoConfigureObservability
class ObservabilityIntegrationTest {

    private static final String SECRET_PASSWORD = "Sup3r-Secret-Pa55";
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    InMemorySpanExporter exporter;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    RestClient.Builder restClientBuilder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearSpans() {
        flush(tracerProvider, exporter);
        exporter.reset();
    }

    // ------------------------------------------------------------- F1: traceparent

    @Test
    void incomingTraceparentIsHonouredAndJdbcSpansJoinTheTrace() throws Exception {
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        HttpResponse<String> r = post(port, "/api/auth/register",
                Map.of("username", unique("trace"), "password", "correct horse battery"),
                traceparent(traceId, parentSpanId));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);

        List<SpanData> spans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getTraceId().equals(traceId)));
        List<SpanData> inTrace = spans.stream().filter(s -> s.getTraceId().equals(traceId)).toList();

        SpanData server = inTrace.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(server.getParentSpanId()).isEqualTo(parentSpanId);
        assertThat(server.getParentSpanContext().isRemote()).isTrue();
        assertThat(server.getName()).isEqualTo("POST /api/auth/register");
        assertThat(server.getAttributes().get(AttributeKey.stringKey("uri"))).isEqualTo("/api/auth/register");
        assertThat(server.getResource().getAttribute(AttributeKey.stringKey("service.name"))).isEqualTo("auth");

        List<SpanData> jdbcSpans = inTrace.stream().filter(ObservabilityIntegrationTest::isJdbcQuery).toList();
        assertThat(jdbcSpans).as("JDBC query spans in %s", inTrace).isNotEmpty();
        assertThat(jdbcSpans).anySatisfy(s -> assertThat(sql(s)).containsIgnoringCase("insert into auth.users"));
        for (SpanData s : jdbcSpans) {
            assertThat(s.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(s.getResource().getAttribute(AttributeKey.stringKey("service.name"))).isEqualTo("auth");
            assertThat(isDescendantOf(s, server, inTrace)).as("%s under the server span", s.getName()).isTrue();
            // SQL text with placeholders, never the bound values.
            assertThat(s.getAttributes().asMap().keySet()).noneMatch(k -> k.getKey().startsWith("jdbc.params"));
        }
        // Lean: no connection or result-set spans.
        assertThat(inTrace).allMatch(s -> s.getKind() == SpanKind.SERVER || isJdbcQuery(s));
    }

    @Test
    void w3cOnlyB3HeadersAreIgnored() throws Exception {
        String b3TraceId = randomTraceId();
        HttpResponse<String> r = get(port, "/api/auth/.well-known/jwks.json", Map.of(
                "b3", b3TraceId + "-" + randomSpanId() + "-1",
                "X-B3-TraceId", b3TraceId, "X-B3-SpanId", randomSpanId(), "X-B3-Sampled", "1"));
        assertThat(r.statusCode()).isEqualTo(200);
        List<SpanData> spans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER));
        SpanData server = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(server.getTraceId()).isNotEqualTo(b3TraceId);
        assertThat(server.getParentSpanContext().isValid()).isFalse();
    }

    // ------------------------------------------------------- F2: no OTLP endpoint

    @Test
    void withoutAnOtlpEndpointThereIsNoOtlpExporterButSpansAreStillCreated(CapturedOutput output) throws Exception {
        assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint")).isNull();
        assertThat(context.getBeanNamesForType(OtlpTracingConnectionDetails.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpGrpcSpanExporter.class)).isEmpty();
        // The only exporter is this test's in-memory one.
        assertThat(context.getBeansOfType(SpanExporter.class).values()).containsExactly(exporter);
        assertThat(context.getBean(Tracer.class)).isNotEqualTo(Tracer.NOOP);

        HttpResponse<String> r = get(port, "/api/auth/.well-known/jwks.json", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(awaitSpans(tracerProvider, exporter, all -> !all.isEmpty())).isNotEmpty();
        assertThat(output.getAll()).doesNotContainIgnoringCase("otlp")
                .doesNotContain("Failed to export")
                .doesNotContain("4318")
                .doesNotContain("ConnectException");
    }

    // ----------------------------------------------------- F3: no secrets anywhere

    @Test
    void passwordsAndHashesNeverReachSpansOrLogs(CapturedOutput output) throws Exception {
        String username = unique("Secret");
        Map<String, String> creds = Map.of("username", username, "password", SECRET_PASSWORD);

        assertThat(post(port, "/api/auth/register", creds, null).statusCode()).isEqualTo(201);
        HttpResponse<String> login = post(port, "/api/auth/login", creds, null);
        assertThat(login.statusCode()).isEqualTo(200);
        String token = json.readTree(login.body()).get("token").asText();
        assertThat(get(port, "/api/auth/me", Map.of("Authorization", "Bearer " + token)).statusCode()).isEqualTo(200);
        // Failures and validation errors too: they take different code paths.
        assertThat(post(port, "/api/auth/login", Map.of("username", username, "password", SECRET_PASSWORD + "x"), null)
                .statusCode()).isEqualTo(401);
        assertThat(post(port, "/api/auth/register", creds, null).statusCode()).isEqualTo(409);
        assertThat(post(port, "/api/auth/register", Map.of("username", "x", "password", SECRET_PASSWORD), null)
                .statusCode()).isEqualTo(400);

        String hash = jdbc.sql("SELECT password_hash FROM auth.users WHERE username = :u")
                .param("u", username).query(String.class).single();
        assertThat(hash).startsWith("$2a$");

        // Six server spans, plus their JDBC children.
        List<SpanData> spans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().filter(s -> s.getKind() == SpanKind.SERVER).count() >= 6);
        assertThat(spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).count()).isGreaterThanOrEqualTo(6);
        assertThat(spans).anyMatch(ObservabilityIntegrationTest::isJdbcQuery);

        String everything = String.join("\n", spanText(spans));
        assertThat(everything).doesNotContain(SECRET_PASSWORD).doesNotContain(hash).doesNotContain(token);
        // The bcrypt salt+hash part alone, in case something re-encodes the prefix.
        assertThat(everything).doesNotContain(hash.substring(7));

        String logs = output.getAll();
        assertThat(logs).as("debug logs were on").contains("/api/auth/login");
        assertThat(logs).doesNotContain(SECRET_PASSWORD).doesNotContain(hash).doesNotContain(hash.substring(7));
    }

    // ------------------------------------------ F4: Prometheus on the management port

    @Test
    void prometheusIsServedOnTheManagementPortOnly() throws Exception {
        // Make sure there is at least one observed request and query.
        assertThat(post(port, "/api/auth/login", Map.of("username", unique("nobody"), "password", "whatever1"), null)
                .statusCode()).isEqualTo(401);
        awaitSpans(tracerProvider, exporter, all -> all.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER));

        HttpResponse<String> r = get(managementPort, "/actuator/prometheus", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/plain"));
        String body = r.body();
        assertThat(body).contains("# TYPE http_server_requests_seconds")
                .contains("http_server_requests_seconds_count{application=\"auth\"")
                .contains("uri=\"/api/auth/login\"")
                .contains("hikaricp_connections_active{application=\"auth\"")
                .contains("jdbc_query_seconds_count{application=\"auth\"")
                .contains("jvm_memory_used_bytes{application=\"auth\"");
        // Actuator traffic is not observed, so it does not show up in the request metrics.
        assertThat(body).doesNotContain("uri=\"/actuator");

        assertThat(get(port, "/actuator/prometheus", Map.of()).statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/health", Map.of()).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------- F5: actuator is not traced

    @Test
    void actuatorRequestsProduceNoSpans() throws Exception {
        String traceId = randomTraceId();
        Map<String, String> headers = Map.of("traceparent", traceparent(traceId, randomSpanId()));
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                "/actuator/prometheus", "/actuator", "/actuator/env")) {
            get(managementPort, path, headers);
        }
        // Control: an API request in the same window is traced, so the pipeline is live.
        get(port, "/api/auth/.well-known/jwks.json", headers);
        List<SpanData> spans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER));
        Thread.sleep(300);
        spans = flush(tracerProvider, exporter);

        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().getName()).isEqualTo("GET /api/auth/.well-known/jwks.json");
        assertThat(String.join("\n", spanText(spans))).doesNotContain("/actuator");
    }

    @Test
    void jdbcOutsideAnyObservationIsNotTraced() throws Exception {
        assertThat(jdbc.sql("SELECT count(*) FROM auth.users").query(Long.class).single()).isNotNegative();
        Thread.sleep(200);
        assertThat(flush(tracerProvider, exporter)).isEmpty();
    }

    // -------------------------------------------- F6: trace ids in plain-text logs

    @Test
    void logLinesInsideARequestCarryItsTraceAndSpanIds(CapturedOutput output) throws Exception {
        String traceId = randomTraceId();
        String marker = UUID.randomUUID().toString();
        HttpResponse<String> r = get(port, "/api/auth/.well-known/jwks.json?probe=" + marker,
                Map.of("traceparent", traceparent(traceId, randomSpanId())));
        assertThat(r.statusCode()).isEqualTo(200);
        SpanData server = awaitSpans(tracerProvider, exporter,
                all -> all.stream().anyMatch(s -> s.getTraceId().equals(traceId))).stream()
                .filter(s -> s.getTraceId().equals(traceId)).findFirst().orElseThrow();

        // DispatcherServlet (DEBUG) logs the request line and "Completed 200 OK" inside the request.
        List<String> lines = output.getAll().lines().toList();
        int start = indexOf(lines, marker);
        assertThat(start).as("request log line in\n%s", output.getAll()).isNotNegative();
        String requestLine = lines.get(start);
        String completed = lines.subList(start, lines.size()).stream()
                .filter(l -> l.contains("Completed 200 OK")).findFirst().orElseThrow();

        // Boot's default correlation pattern: "[<traceId>-<spanId>]".
        String correlation = "[" + traceId + "-" + server.getSpanId() + "]";
        assertThat(requestLine).doesNotStartWith("{").contains(correlation).contains("[scarcity-auth]");
        assertThat(completed).contains(correlation);
        assertThat(HEX16.matcher(server.getSpanId()).matches()).isTrue();
    }

    // ------------------------------------------- F7: OTel semconv span attributes

    private static final AttributeKey<String> METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<Long> STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> URL_SCHEME = AttributeKey.stringKey("url.scheme");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");

    @Test
    void serverSpansCarryOtelSemconvAttributes() throws Exception {
        String registered = randomTraceId();
        String unauthorized = randomTraceId();
        String notFound = randomTraceId();
        String username = unique("semconv");
        assertThat(post(port, "/api/auth/register", Map.of("username", username, "password", SECRET_PASSWORD),
                traceparent(registered, randomSpanId())).statusCode()).isEqualTo(201);
        assertThat(post(port, "/api/auth/login", Map.of("username", username, "password", "wrong-password"),
                traceparent(unauthorized, randomSpanId())).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/auth/no-such-thing?q=secret-query",
                Map.of("traceparent", traceparent(notFound, randomSpanId()))).statusCode()).isEqualTo(404);

        List<SpanData> spans = awaitSpans(tracerProvider, exporter, all -> List.of(registered, unauthorized, notFound)
                .stream().allMatch(t -> all.stream().anyMatch(s -> s.getTraceId().equals(t) && s.getKind() == SpanKind.SERVER)));

        SpanData reg = serverSpan(spans, registered);
        System.err.println("server span attributes (register): " + reg.getAttributes());
        assertThat(reg.getName()).isEqualTo("POST /api/auth/register");
        assertThat(reg.getAttributes().get(METHOD)).isEqualTo("POST");
        assertThat(reg.getAttributes().get(STATUS_CODE)).isEqualTo(201L);
        assertThat(reg.getAttributes().get(ROUTE)).isEqualTo("/api/auth/register");
        assertThat(reg.getAttributes().get(URL_PATH)).isEqualTo("/api/auth/register");
        assertThat(reg.getAttributes().get(URL_SCHEME)).isEqualTo("http");
        assertThat(reg.getAttributes().get(SERVER_ADDRESS)).isEqualTo("localhost");
        assertThat(reg.getAttributes().get(SERVER_PORT)).isEqualTo((long) port);
        assertThat(reg.getAttributes().get(AttributeKey.stringKey("network.protocol.version"))).isEqualTo("1.1");
        assertThat(reg.getAttributes().get(AttributeKey.stringKey("user_agent.original"))).startsWith("Java-http-client");
        assertThat(reg.getAttributes().get(ERROR_TYPE)).isNull();
        // Micrometer's http.url is replaced by url.path; its low-cardinality keys stay.
        assertThat(reg.getAttributes().get(AttributeKey.stringKey("http.url"))).isNull();
        assertThat(reg.getAttributes().get(AttributeKey.stringKey("uri"))).isEqualTo("/api/auth/register");
        assertThat(reg.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("201");
        // The status code is an int attribute (TraceQL: {span.http.response.status_code = 201}), not a string.
        assertThat(reg.getAttributes().asMap().keySet()).filteredOn(k -> k.getKey().equals("http.response.status_code"))
                .singleElement().extracting(AttributeKey::getType).isEqualTo(AttributeType.LONG);

        SpanData login = serverSpan(spans, unauthorized);
        assertThat(login.getName()).isEqualTo("POST /api/auth/login");
        assertThat(login.getAttributes().get(STATUS_CODE)).isEqualTo(401L);
        assertThat(login.getAttributes().get(ROUTE)).isEqualTo("/api/auth/login");
        // 4xx is not a server error in semconv.
        assertThat(login.getAttributes().get(ERROR_TYPE)).isNull();

        SpanData missing = serverSpan(spans, notFound);
        System.err.println("server span attributes (404): " + missing.getName() + " " + missing.getAttributes());
        assertThat(missing.getAttributes().get(STATUS_CODE)).isEqualTo(404L);
        assertThat(missing.getAttributes().get(METHOD)).isEqualTo("GET");
        assertThat(missing.getAttributes().get(URL_PATH)).isEqualTo("/api/auth/no-such-thing");
        assertThat(missing.getAttributes().get(ROUTE)).isNull();
        assertThat(missing.getName()).isEqualTo("GET");
        // No query string anywhere on the span, and no client address.
        assertThat(String.join("\n", spanText(List.of(missing)))).doesNotContain("secret-query");
        assertThat(missing.getAttributes().asMap().keySet()).extracting(AttributeKey::getKey)
                .doesNotContain("url.query", "client.address", "url.full");
    }

    @Test
    void restClientSpansCarryOtelSemconvAttributes() throws Exception {
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<Void> ok = client.get().uri("/api/auth/.well-known/jwks.json?probe={p}", "client-secret-query")
                .retrieve().toBodilessEntity();
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        HttpStatusCode unauthorized = client.post().uri("/api/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("username", unique("nobody"), "password", "whatever1"))
                .exchange((req, res) -> res.getStatusCode());
        assertThat(unauthorized.value()).isEqualTo(401);

        List<SpanData> spans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().filter(s -> s.getKind() == SpanKind.CLIENT && !isJdbcQuery(s)).count() >= 2
                        && all.stream().filter(s -> s.getKind() == SpanKind.SERVER).count() >= 2);
        List<SpanData> clientSpans = spans.stream().filter(s -> s.getKind() == SpanKind.CLIENT && !isJdbcQuery(s)).toList();
        System.err.println("client spans: " + clientSpans);

        SpanData get = clientSpans.stream().filter(s -> "GET".equals(s.getAttributes().get(METHOD))).findFirst().orElseThrow();
        assertThat(get.getName()).isEqualTo("GET /api/auth/.well-known/jwks.json");
        assertThat(get.getAttributes().get(STATUS_CODE)).isEqualTo(200L);
        assertThat(get.getAttributes().get(AttributeKey.stringKey("url.full")))
                .isEqualTo("http://localhost:" + port + "/api/auth/.well-known/jwks.json");
        assertThat(get.getAttributes().get(SERVER_ADDRESS)).isEqualTo("localhost");
        assertThat(get.getAttributes().get(SERVER_PORT)).isEqualTo((long) port);
        assertThat(get.getAttributes().get(ERROR_TYPE)).isNull();
        assertThat(get.getAttributes().get(AttributeKey.stringKey("http.url"))).isNull();
        assertThat(String.join("\n", spanText(List.of(get)))).doesNotContain("client-secret-query");
        // The client sent traceparent: the server span is its child.
        SpanData serverOfGet = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER
                && s.getParentSpanId().equals(get.getSpanId())).findFirst().orElseThrow();
        assertThat(serverOfGet.getTraceId()).isEqualTo(get.getTraceId());

        SpanData post = clientSpans.stream().filter(s -> "POST".equals(s.getAttributes().get(METHOD))).findFirst().orElseThrow();
        assertThat(post.getName()).isEqualTo("POST /api/auth/login");
        assertThat(post.getAttributes().get(STATUS_CODE)).isEqualTo(401L);
        // For clients a 4xx is an error.
        assertThat(post.getAttributes().get(ERROR_TYPE)).isEqualTo("401");

        // Client metrics keep Spring's tags.
        String prometheus = get(managementPort, "/actuator/prometheus", Map.of()).body();
        assertThat(tagKeys(prometheus, "http_client_requests_seconds_count"))
                .containsExactly("application", "client_name", "error", "exception", "method", "outcome", "status", "uri");
    }

    @Test
    void jdbcSpansCarryDbSystemAndQueryText() throws Exception {
        String traceId = randomTraceId();
        assertThat(post(port, "/api/auth/register", Map.of("username", unique("db"), "password", SECRET_PASSWORD),
                traceparent(traceId, randomSpanId())).statusCode()).isEqualTo(201);
        List<SpanData> jdbcSpans = awaitSpans(tracerProvider, exporter,
                all -> all.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getTraceId().equals(traceId)))
                .stream().filter(s -> s.getTraceId().equals(traceId) && isJdbcQuery(s)).toList();
        assertThat(jdbcSpans).isNotEmpty();
        System.err.println("jdbc span attributes: " + jdbcSpans.getFirst().getAttributes());
        for (SpanData s : jdbcSpans) {
            assertThat(s.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("postgresql");
            assertThat(s.getAttributes().get(AttributeKey.stringKey("db.system.name"))).isEqualTo("postgresql");
            assertThat(s.getAttributes().get(AttributeKey.stringKey("db.query.text")))
                    .isEqualTo(s.getAttributes().get(AttributeKey.stringKey("jdbc.query[0]")))
                    .contains("?");
        }
        assertThat(jdbcSpans).anySatisfy(s -> assertThat(s.getAttributes().get(AttributeKey.stringKey("db.query.text")))
                .containsIgnoringCase("insert into auth.users"));
    }

    @Test
    void semconvKeysNeverReachMetrics() throws Exception {
        assertThat(post(port, "/api/auth/login", Map.of("username", unique("nobody"), "password", "whatever1"), null)
                .statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/auth/.well-known/jwks.json", Map.of()).statusCode()).isEqualTo(200);
        awaitSpans(tracerProvider, exporter, all -> all.stream().filter(s -> s.getKind() == SpanKind.SERVER).count() >= 2);

        String body = get(managementPort, "/actuator/prometheus", Map.of()).body();
        assertThat(tagKeys(body, "http_server_requests_seconds_count"))
                .containsExactly("application", "error", "exception", "method", "outcome", "status", "uri");
        assertThat(tagKeys(body, "jdbc_query_seconds_count"))
                .containsExactly("application", "error", "jdbc_datasource_driver", "jdbc_datasource_name",
                        "jdbc_datasource_pool");
        for (String key : List.of("http.request.method", "http_request_method", "http.response.status_code",
                "http_response_status_code", "url.path", "url_path", "url_scheme", "url_full", "http_route",
                "error_type", "server_address", "server_port", "network_protocol_version", "user_agent",
                "db_system", "db_query_text", "db.query.text")) {
            assertThat(body).as(key).doesNotContain(key);
        }
    }

    /** The union of label names on every sample line of {@code metric}. */
    private static Set<String> tagKeys(String prometheus, String metric) {
        Set<String> keys = new TreeSet<>();
        Pattern label = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\"");
        prometheus.lines().filter(l -> l.startsWith(metric + "{")).forEach(l -> {
            Matcher m = label.matcher(l.substring(metric.length() + 1, l.lastIndexOf('}')));
            while (m.find()) {
                keys.add(m.group(1));
            }
        });
        assertThat(keys).as("samples of %s", metric).isNotEmpty();
        return keys;
    }

    private static SpanData serverSpan(List<SpanData> spans, String traceId) {
        return spans.stream().filter(s -> s.getTraceId().equals(traceId) && s.getKind() == SpanKind.SERVER)
                .findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- helpers

    static boolean isJdbcQuery(SpanData s) {
        return s.getAttributes().asMap().keySet().stream().anyMatch(k -> k.getKey().startsWith("jdbc.query"));
    }

    private static String sql(SpanData s) {
        return s.getAttributes().asMap().entrySet().stream()
                .filter(e -> e.getKey().getKey().startsWith("jdbc.query"))
                .map(e -> String.valueOf(e.getValue())).findFirst().orElse("");
    }

    private static boolean isDescendantOf(SpanData span, SpanData ancestor, List<SpanData> trace) {
        String parent = span.getParentSpanId();
        for (int hops = 0; hops < 20; hops++) {
            if (parent.equals(ancestor.getSpanId())) {
                return true;
            }
            String current = parent;
            parent = trace.stream().filter(s -> s.getSpanId().equals(current)).map(SpanData::getParentSpanId)
                    .findFirst().orElse(null);
            if (parent == null) {
                return false;
            }
        }
        return false;
    }

    /** Every string a span carries: name, status, attributes, events and links. */
    static List<String> spanText(List<SpanData> spans) {
        List<String> out = new ArrayList<>();
        for (SpanData s : spans) {
            out.add(s.getName());
            out.add(s.getStatus().getDescription());
            s.getAttributes().forEach((k, v) -> out.add(k.getKey() + "=" + v));
            for (EventData e : s.getEvents()) {
                out.add(e.getName());
                e.getAttributes().forEach((k, v) -> out.add(k.getKey() + "=" + v));
            }
            s.getLinks().forEach(l -> l.getAttributes().forEach((k, v) -> out.add(k.getKey() + "=" + v)));
        }
        return out;
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> post(int atPort, String path, Object body, String traceparent) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + atPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return send(b);
    }

    private HttpResponse<String> get(int atPort, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + atPort + path)).GET();
        headers.forEach(b::header);
        return send(b);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
