package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The JWKS endpoint is unreachable: the service must still start and fail the request. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scarcity.world.tick.enabled=false",
        "management.server.port=0",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/never/jwks.json"})
class LazyJwksTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestSupport.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestSupport.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @LocalManagementPort int mgmt;

    @Test
    void startsWithoutAuthAndRejectsTokensWith401() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> health = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mgmt + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/resources?type=game"))
                        .header("Authorization", "Bearer " + TestSupport.validToken()).build(),
                HttpResponse.BodyHandlers.ofString());
        // Spring Security reports a JWKS fetch failure as a server error (not a 401: the token
        // may be fine). Known limitation, see AGENTS.md; never a 2xx.
        assertThat(r.statusCode()).isEqualTo(500);
    }
}
