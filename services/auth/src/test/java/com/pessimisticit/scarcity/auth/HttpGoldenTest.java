package com.pessimisticit.scarcity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What auth's API sends, byte for byte ({@link Golden}): users, tokens, the
 * JWKS, the service-token response and problem+json errors, one line per
 * request ({@code METHOD path body -> status content-type body}). Generated
 * values (tokens, ids, times, the key's modulus) are masked; keys, their
 * order and the rest of each body are not. A changed line is a contract change (#246).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.password.bcrypt-strength=10")
@Import(TestcontainersConfiguration.class)
class HttpGoldenTest {

    private static final String SERVICE_KEY = "golden-test-key-9a1e5c";
    private static final String PASSWORD = "correct horse battery";
    private static final String USER = "{\"username\":\"Golden_Ada\",\"password\":\"" + PASSWORD + "\"}";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("auth.service-token.keys-dir", HttpGoldenTest::keysDir);
    }

    private static String keysDir() {
        try {
            Path dir = Files.createTempDirectory("service-api-keys");
            Files.writeString(dir.resolve("orbital"), SERVICE_KEY + "\n");
            return dir.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> lines = new ArrayList<>();

    private static String masked(String body) {
        return body.replaceAll("ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "<jwt>")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<instant>")
                .replaceAll("\"n\":\"[A-Za-z0-9_-]+\"", "\"n\":\"<n>\"")
                .replaceAll("\"kid\":\"[^\"]+\"", "\"kid\":\"<kid>\"");
    }

    private HttpResponse<String> send(String line, HttpRequest.Builder b) throws Exception {
        HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        lines.add(masked(line) + " -> " + r.statusCode() + " "
                + r.headers().firstValue("Content-Type").orElse("-") + " " + masked(r.body()));
        return r;
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return send("POST " + path + " " + body, b);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send("GET " + path, b);
    }

    @Test
    void usersTokensKeysAndProblemsAreSentAsTheirGoldens() throws Exception {
        post("/api/auth/register", USER);
        post("/api/auth/register", USER);
        post("/api/auth/register", "{\"username\":\"no spaces!\",\"password\":\"short\"}");
        post("/api/auth/register", "{\"username\":1,\"password\":2}");
        post("/api/auth/register", "{\"username\":");
        String token = new ObjectMapper()
                .readTree(post("/api/auth/login", USER).body()).get("token").asText();
        post("/api/auth/login", USER.replace(PASSWORD, "wrong password"));
        get("/api/auth/me", token);
        get("/api/auth/me", null);
        get("/api/auth/me", "not.a.jwt");
        get("/api/auth/.well-known/jwks.json", null);
        post("/api/auth/service-token", "", "X-Api-Key", SERVICE_KEY);
        post("/api/auth/service-token", "", "X-Api-Key", "not-a-key");

        Golden.assertLines("http.txt", lines);
        assertThat(lines).hasSize(13);
    }
}
