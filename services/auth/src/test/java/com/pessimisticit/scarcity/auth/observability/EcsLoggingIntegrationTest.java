package com.pessimisticit.scarcity.auth.observability;

import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.awaitSpans;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.randomSpanId;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.randomTraceId;
import static com.pessimisticit.scarcity.auth.observability.InMemorySpans.traceparent;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.auth.TestcontainersConfiguration;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * The k8s profile logs ECS JSON, one object per line, with the trace and span ids of the
 * request. Runs in its own surefire execution (see pom.xml): Boot configures logback once
 * per JVM, so in a JVM where another test context already started, this context would
 * keep the plain-text format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.password.bcrypt-strength=10",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.jdbc=DEBUG",
})
@ActiveProfiles("k8s")
@Import({ TestcontainersConfiguration.class, InMemorySpans.class })
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureObservability
class EcsLoggingIntegrationTest {

    private static final String SECRET_PASSWORD = "Sup3r-Secret-Pa55";

    @LocalServerPort
    int port;

    @Autowired
    InMemorySpanExporter exporter;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void everyLineIsEcsJsonAndLinesInsideARequestCarryItsTraceAndSpanIds(CapturedOutput output) throws Exception {
        int from = output.getAll().length();
        String traceId = randomTraceId();
        String marker = UUID.randomUUID().toString();
        HttpResponse<String> r = send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/auth/.well-known/jwks.json?probe=" + marker))
                .header("traceparent", traceparent(traceId, randomSpanId())).GET());
        assertThat(r.statusCode()).isEqualTo(200);
        SpanData server = awaitSpans(tracerProvider, exporter, all -> all.stream()
                .anyMatch(s -> s.getTraceId().equals(traceId) && s.getKind() == SpanKind.SERVER)).stream()
                .filter(s -> s.getTraceId().equals(traceId)).findFirst().orElseThrow();

        List<JsonNode> lines = jsonLines(output, from);
        // DispatcherServlet (DEBUG) logs the request line and "Completed 200 OK" for this request.
        List<JsonNode> inRequest = lines.stream()
                .filter(l -> traceId.equals(l.path("traceId").asText(null))).toList();
        assertThat(inRequest).as("lines of trace %s in\n%s", traceId, output.getAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(inRequest).anyMatch(l -> l.get("message").asText().contains(marker));
        assertThat(inRequest).anyMatch(l -> l.get("message").asText().contains("Completed 200 OK"));
        for (JsonNode line : inRequest) {
            // Micrometer Tracing's MDC keys, emitted by Boot's ECS formatter as top-level fields.
            assertThat(line.get("traceId").asText()).isEqualTo(traceId).matches("[0-9a-f]{32}");
            assertThat(line.get("spanId").asText()).isEqualTo(server.getSpanId()).matches("[0-9a-f]{16}");
            // Grafana's Loki-to-Tempo link keys on the top-level "traceId" field: it must not
            // move to ECS's nested trace.id / span.id (or a dotted "trace.id" key).
            assertThat(line.has("trace")).as("nested trace object in %s", line).isFalse();
            assertThat(line.has("trace.id")).isFalse();
            assertThat(line.has("span")).isFalse();
            assertThat(line.get("traceId").isTextual()).isTrue();
            assertThat(line.at("/service/name").asText()).isEqualTo("auth");
            // service.node.name comes from HOSTNAME; when that is unset or empty it is omitted.
            String hostname = System.getenv("HOSTNAME");
            if (hostname == null || hostname.isEmpty()) {
                assertThat(line.at("/service/node").has("name")).as(line.toString()).isFalse();
            } else {
                assertThat(line.at("/service/node/name").asText()).isEqualTo(hostname);
            }
            assertThat(line.at("/ecs/version").asText()).isNotBlank();
            assertThat(line.at("/log/level").asText()).isNotBlank();
            assertThat(line.at("/log/logger").asText()).isNotBlank();
            assertThat(line.has("@timestamp")).isTrue();
        }
    }

    @Test
    void passwordsAndHashesNeverReachTheJsonLogs(CapturedOutput output) throws Exception {
        int from = output.getAll().length();
        String username = "Ecs_" + UUID.randomUUID().toString().substring(0, 8);
        String creds = json.writeValueAsString(Map.of("username", username, "password", SECRET_PASSWORD));
        assertThat(post("/api/auth/register", creds).statusCode()).isEqualTo(201);
        assertThat(post("/api/auth/login", creds).statusCode()).isEqualTo(200);
        String hash = jdbc.sql("SELECT password_hash FROM auth.users WHERE username = :u")
                .param("u", username).query(String.class).single();

        jsonLines(output, from);
        assertThat(output.getAll()).contains("/api/auth/login")
                .doesNotContain(SECRET_PASSWORD).doesNotContain(hash).doesNotContain(hash.substring(7));
    }

    /**
     * Parses every line captured since {@code from}; fails if any is not a JSON object.
     * Output from before the test body is skipped: when this context starts after another
     * test class's, Boot's first "Starting ..." line still uses the previous logging setup.
     */
    private List<JsonNode> jsonLines(CapturedOutput output, int from) {
        return output.getAll().substring(from).lines().filter(l -> !l.isBlank()).map(l -> {
            try {
                JsonNode node = json.readTree(l);
                assertThat(node.isObject()).as(l).isTrue();
                return node;
            } catch (Exception e) {
                throw new AssertionError("not a JSON log line: " + l, e);
            }
        }).toList();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
