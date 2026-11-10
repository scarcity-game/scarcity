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
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    // ------------------------------------------------------------- audit lines

    private static final String AUDIT_LOGGER = "com.pessimisticit.scarcity.auth.audit";

    @Test
    void auditLinesCarryTheRequestTraceIdAndEcsFieldsAndNoCredentials(CapturedOutput output) throws Exception {
        int from = output.getAll().length();
        String username = "Audit_" + UUID.randomUUID().toString().substring(0, 8);
        String unknown = "Nobody_" + UUID.randomUUID().toString().substring(0, 8);
        String wrongPassword = SECRET_PASSWORD + "-wrong";
        String malformed = "not a valid name!";
        String tooLong = "p".repeat(73);

        record Call(String path, String username, String password, int status, String traceId) {
        }
        List<Call> calls = List.of(
                new Call("/api/auth/register", username, SECRET_PASSWORD, 201, randomTraceId()),
                new Call("/api/auth/register", username.toUpperCase(), SECRET_PASSWORD, 409, randomTraceId()),
                new Call("/api/auth/login", username, SECRET_PASSWORD, 200, randomTraceId()),
                new Call("/api/auth/login", username, wrongPassword, 401, randomTraceId()),
                new Call("/api/auth/login", unknown, SECRET_PASSWORD, 401, randomTraceId()),
                new Call("/api/auth/login", malformed, SECRET_PASSWORD, 401, randomTraceId()),
                new Call("/api/auth/login", username, tooLong, 401, randomTraceId()));
        String userId = null;
        for (Call c : calls) {
            HttpResponse<String> r = post(c.path(),
                    json.writeValueAsString(Map.of("username", c.username(), "password", c.password())),
                    traceparent(c.traceId(), randomSpanId()));
            assertThat(r.statusCode()).as("%s %s: %s", c.path(), c.username(), r.body()).isEqualTo(c.status());
            if (c.status() == 201) {
                userId = json.readTree(r.body()).get("id").asText();
            }
        }
        // Server spans end after the response; wait for all of them so every line was written.
        awaitSpans(tracerProvider, exporter, all -> calls.stream().allMatch(c -> all.stream()
                .anyMatch(s -> s.getTraceId().equals(c.traceId()) && s.getKind() == SpanKind.SERVER)));
        List<SpanData> spans = InMemorySpans.flush(tracerProvider, exporter);

        List<JsonNode> audit = jsonLines(output, from).stream()
                .filter(l -> AUDIT_LOGGER.equals(l.at("/log/logger").asText())).toList();
        JsonNode[] lineOf = new JsonNode[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            Call c = calls.get(i);
            List<JsonNode> mine = audit.stream().filter(l -> c.traceId().equals(l.path("traceId").asText(null))).toList();
            assertThat(mine).as("audit lines for %s %s in\n%s", c.path(), c.username(), audit).hasSize(1);
            JsonNode line = mine.getFirst();
            lineOf[i] = line;
            SpanData server = spans.stream()
                    .filter(s -> s.getTraceId().equals(c.traceId()) && s.getKind() == SpanKind.SERVER)
                    .findFirst().orElseThrow();
            assertThat(line.get("traceId").isTextual()).isTrue();
            assertThat(line.get("spanId").asText()).isEqualTo(server.getSpanId());
            assertThat(line.has("trace")).as("nested trace object in %s", line).isFalse();
            assertThat(line.has("trace.id")).isFalse();
            assertThat(line.has("span")).isFalse();
            assertThat(line.at("/log/level").asText()).isEqualTo("INFO");
            assertThat(line.at("/service/name").asText()).isEqualTo("auth");
            // Key-value pairs with dotted keys are nested ECS objects, not flat "event.action" keys.
            assertThat(line.has("event.action")).isFalse();
            assertThat(line.at("/event/action").asText()).isEqualTo(c.path().endsWith("register") ? "register" : "login");
            String text = line.toString();
            assertThat(text).doesNotContain(SECRET_PASSWORD).doesNotContain(tooLong);
        }

        // register 201
        assertThat(lineOf[0].get("message").asText()).isEqualTo("register succeeded for user " + userId);
        assertThat(lineOf[0].at("/event/outcome").asText()).isEqualTo("success");
        assertThat(lineOf[0].at("/user/id").asText()).isEqualTo(userId);
        assertThat(lineOf[0].at("/event").has("reason")).isFalse();
        // register 409: no username, no user id
        assertThat(lineOf[1].get("message").asText()).isEqualTo("register failed: username_taken");
        assertThat(lineOf[1].at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(lineOf[1].at("/event/reason").asText()).isEqualTo("username_taken");
        assertThat(lineOf[1].has("user")).isFalse();
        assertThat(lineOf[1].toString()).doesNotContainIgnoringCase(username);
        // login 200
        assertThat(lineOf[2].get("message").asText()).isEqualTo("login succeeded for user " + userId);
        assertThat(lineOf[2].at("/event/outcome").asText()).isEqualTo("success");
        assertThat(lineOf[2].at("/user/id").asText()).isEqualTo(userId);
        // the four 401s: identical apart from timestamp, ids and thread; no username or password
        for (int i = 3; i < 7; i++) {
            JsonNode line = lineOf[i];
            assertThat(line.get("message").asText()).isEqualTo("login failed: invalid_credentials");
            assertThat(line.at("/event/outcome").asText()).isEqualTo("failure");
            assertThat(line.at("/event/reason").asText()).isEqualTo("invalid_credentials");
            assertThat(line.has("user")).isFalse();
            assertThat(line.toString()).doesNotContainIgnoringCase(username).doesNotContain(unknown)
                    .doesNotContain(malformed).doesNotContain(wrongPassword);
            assertThat(withoutVaryingFields(line)).isEqualTo(withoutVaryingFields(lineOf[3]));
        }
        // An example line, for the record.
        System.err.println("example audit lines:\n" + lineOf[2] + "\n" + lineOf[4]);
    }

    private static JsonNode withoutVaryingFields(JsonNode line) {
        ObjectNode copy = line.deepCopy();
        copy.remove(List.of("@timestamp", "traceId", "spanId"));
        ((ObjectNode) copy.get("process")).remove("thread");
        return copy;
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
        return post(path, body, null);
    }

    private HttpResponse<String> post(String path, String body, String traceparent) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return send(b);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
