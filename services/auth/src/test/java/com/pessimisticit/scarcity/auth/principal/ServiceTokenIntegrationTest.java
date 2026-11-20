package com.pessimisticit.scarcity.auth.principal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pessimisticit.scarcity.auth.TestcontainersConfiguration;

/** {@code POST /api/auth/service-token} (root AGENTS.md, "Auth"; #147). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.password.bcrypt-strength=10")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class ServiceTokenIntegrationTest {

    private static final String ORBITAL_KEY = "orbital-test-key-5f0c2a9e41d7";
    private static final String PATH = "/api/auth/service-token";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("auth.service-token.keys-dir", ServiceTokenIntegrationTest::keysDir);
    }

    private static String keysDir() {
        try {
            Path dir = Files.createTempDirectory("service-api-keys");
            Files.writeString(dir.resolve("orbital"), ORBITAL_KEY + "\n");
            return dir.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http;

    ServiceTokenIntegrationTest() {
        this.http = HttpClient.newHttpClient();
    }

    @Test
    void aValidKeyGetsAServiceTokenThatVerifiesAgainstTheJwks() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        HttpResponse<String> r = exchange(ORBITAL_KEY);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json.readTree(r.body());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("token", "expiresIn");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);

        SignedJWT jwt = SignedJWT.parse(body.get("token").asText());
        JWKSet jwks = JWKSet.parse(send(HttpRequest.newBuilder(uri("/api/auth/.well-known/jwks.json"))).body());
        RSAKey key = (RSAKey) jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) key.toRSAPublicKey()))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "kind", "iat", "exp");
        assertThat(claims.getIssuer()).isEqualTo("scarcity-auth");
        assertThat(claims.getSubject()).isEqualTo("orbital");
        assertThat(claims.getStringClaim("kind")).isEqualTo("service");
        assertThat(claims.getIssueTime().toInstant()).isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aMissingAndAnUnknownKeyGetTheSame401() throws Exception {
        List<HttpResponse<String>> refused = new ArrayList<>();
        refused.add(send(HttpRequest.newBuilder(uri(PATH)).POST(HttpRequest.BodyPublishers.noBody())));
        for (String bad : new String[] {"", "not-a-key", ORBITAL_KEY + "x", ORBITAL_KEY.toUpperCase()}) {
            refused.add(exchange(bad));
        }
        for (HttpResponse<String> r : refused) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                    ct -> assertThat(ct).startsWith("application/problem+json"));
            assertThat(r.body()).isEqualTo(refused.get(0).body());
        }
    }

    @Test
    void aServiceTokenIsNotAUserToken() throws Exception {
        String token = json.readTree(exchange(ORBITAL_KEY).body()).get("token").asText();
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me"))
                .header("Authorization", "Bearer " + token));
        assertThat(me.statusCode()).isEqualTo(401);
    }

    @Test
    void noKeyEverReachesTheLogs(CapturedOutput output) throws Exception {
        exchange(ORBITAL_KEY);
        exchange("wrong-key-" + ORBITAL_KEY);
        assertThat(output.getAll())
                .contains("service-token issued for principal orbital")
                .contains("service-token refused: invalid_key")
                .doesNotContain(ORBITAL_KEY);
    }

    private HttpResponse<String> exchange(String key) throws Exception {
        return send(HttpRequest.newBuilder(uri(PATH)).header("X-Api-Key", key)
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
