package com.pessimisticit.scarcity.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.auth.TestcontainersConfiguration;

/** With Postgres stopped, endpoints that need it answer 503 + Retry-After, not 500 (#6). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"auth.password.bcrypt-strength=10", "spring.datasource.hikari.connection-timeout=1000",
                "auth.unavailable.retry-after=7s"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseUnavailableIntegrationTest {

    private static final String CREDENTIALS = "{\"username\":\"%s\",\"password\":\"correct horse battery\"}";

    @LocalServerPort
    int port;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void endpointsThatNeedTheDatabaseAnswer503WhileItIsDown() throws Exception {
        assertThat(send("POST", "/api/auth/register", CREDENTIALS.formatted("before_down")).statusCode()).isEqualTo(201);
        postgres.stop();

        assertUnavailable(send("POST", "/api/auth/register", CREDENTIALS.formatted("during_down")));
        assertUnavailable(send("POST", "/api/auth/login", CREDENTIALS.formatted("before_down")));

        // Endpoints that never touch the database are unaffected.
        assertThat(send("GET", "/api/auth/.well-known/jwks.json", null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/auth/service-token", null).statusCode()).isEqualTo(401);
        // Invalid input is rejected before the database is reached.
        assertThat(send("POST", "/api/auth/register", "{\"username\":\"x\",\"password\":\"y\"}").statusCode())
                .isEqualTo(400);
    }

    private void assertUnavailable(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("7");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("detail").asText()).doesNotContain("jdbc", "Hikari");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
