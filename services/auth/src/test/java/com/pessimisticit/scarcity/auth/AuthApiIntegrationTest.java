package com.pessimisticit.scarcity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.pessimisticit.scarcity.auth.jwt.TokenService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.password.bcrypt-strength=10")
@Import(TestcontainersConfiguration.class)
// Boot test contexts switch tracing and metrics export off unless asked; run them as in prod.
@AutoConfigureObservability
class AuthApiIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PASSWORD = "correct horse battery";

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TokenService tokens;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- happy path

    @Test
    void registerLoginMe() throws Exception {
        String username = unique("Alice");

        HttpResponse<String> reg = post("/api/auth/register", credentials(username, PASSWORD));
        assertThat(reg.statusCode()).isEqualTo(201);
        assertThat(reg.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        JsonNode user = json.readTree(reg.body());
        assertThat(fieldNames(user)).containsExactlyInAnyOrder("id", "username", "createdAt");
        UUID id = UUID.fromString(user.get("id").asText());
        assertThat(user.get("username").asText()).isEqualTo(username);
        Instant createdAt = Instant.parse(user.get("createdAt").asText());
        assertThat(createdAt).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));

        // Login is case-insensitive on username.
        HttpResponse<String> login = post("/api/auth/login", credentials(username.toUpperCase(), PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode tokenBody = json.readTree(login.body());
        assertThat(fieldNames(tokenBody)).containsExactlyInAnyOrder("token", "tokenType", "expiresAt");
        assertThat(tokenBody.get("tokenType").asText()).isEqualTo("Bearer");
        String token = tokenBody.get("token").asText();
        Instant expiresAt = Instant.parse(tokenBody.get("expiresAt").asText());
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("username")).isEqualTo(username); // as stored
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(expiresAt);
        assertThat(Duration.between(jwt.getJWTClaimsSet().getIssueTime().toInstant(), expiresAt))
                .isEqualTo(Duration.ofHours(1));

        HttpResponse<String> me = get("/api/auth/me", "Bearer " + token);
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode meBody = json.readTree(me.body());
        assertThat(fieldNames(meBody)).containsExactlyInAnyOrder("id", "username", "createdAt");
        assertThat(meBody.get("id").asText()).isEqualTo(id.toString());
        assertThat(meBody.get("username").asText()).isEqualTo(username);
        assertThat(Instant.parse(meBody.get("createdAt").asText())).isEqualTo(createdAt);

        // Scheme is case-insensitive (RFC 7235).
        assertThat(get("/api/auth/me", "bearer " + token).statusCode()).isEqualTo(200);
    }

    @Test
    void passwordIsStoredAsBcryptWithCostAtLeastTen() throws Exception {
        String username = unique("hash");
        assertThat(post("/api/auth/register", credentials(username, PASSWORD)).statusCode()).isEqualTo(201);
        String hash = jdbc.sql("SELECT password_hash FROM auth.users WHERE username = :u")
                .param("u", username).query(String.class).single();
        assertThat(hash).matches("\\$2[aby]\\$\\d\\d\\$.{53}");
        assertThat(Integer.parseInt(hash.substring(4, 6))).isGreaterThanOrEqualTo(10);
        assertThat(hash).doesNotContain(PASSWORD);
    }

    // ---------------------------------------------------------------- register errors

    @Test
    void duplicateUsernameIsConflictIncludingCaseVariant() throws Exception {
        String username = unique("Bob");
        assertThat(post("/api/auth/register", credentials(username, PASSWORD)).statusCode()).isEqualTo(201);

        assertProblem(post("/api/auth/register", credentials(username, PASSWORD)), 409);
        assertProblem(post("/api/auth/register", credentials(username.toLowerCase(), "another password")), 409);
        assertProblem(post("/api/auth/register", credentials(username.toUpperCase(), "another password")), 409);
    }

    @Test
    void concurrentRegistrationOfSameNameYieldsOneCreatedRestConflict() throws Exception {
        String base = unique("race");
        List<String> variants = List.of(base, base.toUpperCase(), base.toLowerCase(), base, base.toUpperCase(),
                base.toLowerCase());
        try (ExecutorService pool = Executors.newFixedThreadPool(variants.size())) {
            List<Callable<Integer>> calls = variants.stream()
                    .<Callable<Integer>>map(name -> () -> post("/api/auth/register", credentials(name, PASSWORD))
                            .statusCode())
                    .toList();
            List<Integer> statuses = pool.invokeAll(calls).stream().map(AuthApiIntegrationTest::join).toList();
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        }
    }

    @Test
    void registerValidationFailures() throws Exception {
        assertValidation(credentials("ab", PASSWORD), "username");
        assertValidation(credentials("a".repeat(33), PASSWORD), "username");
        assertValidation(credentials("bad name", PASSWORD), "username");
        assertValidation(credentials(unique("u"), "short"), "password");
        assertValidation(credentials(unique("u"), "a".repeat(73)), "password");
        assertValidation(credentials(unique("u"), "€".repeat(25)), "password");
        assertValidation(Map.of("password", PASSWORD), "username");
        assertValidation(Map.of("username", unique("u")), "password");
        assertValidation("{\"username\":null,\"password\":null}", "username", "password");
        assertValidation("{}", "username", "password");
    }

    @Test
    void nonStringCredentialsAreRejectedNotCoerced() throws Exception {
        String pw = "\"" + PASSWORD + "\"";
        List<String> badUsernames = List.of("12345678", "1.5e3", "true", "{\"a\":1}", "[\"abc\"]");
        for (String path : List.of("/api/auth/register", "/api/auth/login")) {
            for (String u : badUsernames) {
                assertProblem(postRaw(path, "{\"username\":" + u + ",\"password\":" + pw + "}"), 400);
            }
            for (String p : List.of("12345678", "123456.789", "false", "{\"a\":\"b\"}", "[\"abcdefgh\"]")) {
                assertProblem(postRaw(path, "{\"username\":\"" + unique("coerce") + "\",\"password\":" + p + "}"),
                        400);
            }
        }
        // Nothing was created by the coercion attempts.
        assertThat(jdbc.sql("SELECT count(*) FROM auth.users WHERE username IN ('12345678', '1500.0', 'true')")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void malformedJsonWrongContentTypeAndEmptyBodyAreProblems() throws Exception {
        assertProblem(send(HttpRequest.newBuilder(uri("/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":"))), 400);
        assertProblem(send(HttpRequest.newBuilder(uri("/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())), 400);
        assertProblem(send(HttpRequest.newBuilder(uri("/api/auth/register"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("username=a"))), 415);
        assertProblem(send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=a&password=b"))), 415);
        assertProblem(get("/api/auth/register", null), 405);
        assertProblem(get("/api/auth/does-not-exist", null), 404);
        assertProblem(get("/nope", null), 404);
    }

    // ---------------------------------------------------------------- login errors

    @Test
    void badCredentialsAreIdenticalForUnknownUserAndWrongPassword() throws Exception {
        String username = unique("carol");
        assertThat(post("/api/auth/register", credentials(username, PASSWORD)).statusCode()).isEqualTo(201);

        HttpResponse<String> wrongPassword = post("/api/auth/login", credentials(username, "wrong password"));
        HttpResponse<String> unknownUser = post("/api/auth/login", credentials(unique("nobody"), PASSWORD));
        HttpResponse<String> tooLong = post("/api/auth/login", credentials(username, "a".repeat(100)));
        HttpResponse<String> weirdName = post("/api/auth/login", credentials("not a valid name!", PASSWORD));

        for (HttpResponse<String> r : List.of(wrongPassword, unknownUser, tooLong, weirdName)) {
            assertProblem(r, 401);
            assertThat(r.body()).isEqualTo(wrongPassword.body());
            assertThat(r.headers().map().keySet()).isEqualTo(wrongPassword.headers().map().keySet());
        }
        assertThat(wrongPassword.body()).doesNotContain(username);
    }

    @Test
    void loginWithMissingFieldsIs400() throws Exception {
        assertValidationAt("/api/auth/login", "{}", "username", "password");
        assertValidationAt("/api/auth/login", "{\"username\":\"someone\"}", "password");
    }

    // ---------------------------------------------------------------- /me errors

    @Test
    void meRejectsMissingMalformedForeignAndExpiredTokens() throws Exception {
        String username = unique("dave");
        JsonNode user = json.readTree(post("/api/auth/register", credentials(username, PASSWORD)).body());
        UUID id = UUID.fromString(user.get("id").asText());

        HttpResponse<String> missing = get("/api/auth/me", null);
        assertProblem(missing, 401);
        assertThat(missing.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

        HttpResponse<String> garbage = get("/api/auth/me", "Bearer not.a.jwt");
        assertProblem(garbage, 401);
        assertThat(garbage.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                h -> assertThat(h).contains("invalid_token"));

        assertProblem(get("/api/auth/me", "Basic dXNlcjpwYXNz"), 401);
        assertProblem(get("/api/auth/me", "Bearer"), 401);

        String valid = tokens.mint(id, username).token();
        String[] parts = valid.split("\\.");
        assertProblem(get("/api/auth/me", "Bearer " + parts[0] + "." + parts[1] + "." + "AAAA" + parts[2].substring(4)), 401);

        TokenService otherKey = new TokenService(com.pessimisticit.scarcity.auth.jwt.RsaKeys.generate(2048),
                Duration.ofHours(1), java.time.Clock.systemUTC());
        assertProblem(get("/api/auth/me", "Bearer " + otherKey.mint(id, username).token()), 401);

        String expired = tokens.mint(id, username, Instant.now().minus(Duration.ofHours(2))).token();
        assertProblem(get("/api/auth/me", "Bearer " + expired), 401);

        // Valid signature, but the user no longer exists.
        String ghost = tokens.mint(UUID.randomUUID(), "ghost").token();
        assertProblem(get("/api/auth/me", "Bearer " + ghost), 401);

        assertThat(get("/api/auth/me", "Bearer " + valid).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- JWKS

    @Test
    void jwksOverHttpVerifiesMintedToken() throws Exception {
        String username = unique("erin");
        post("/api/auth/register", credentials(username, PASSWORD));
        String token = json.readTree(post("/api/auth/login", credentials(username, PASSWORD)).body())
                .get("token").asText();

        HttpResponse<String> response = get("/api/auth/.well-known/jwks.json", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));

        JsonNode raw = json.readTree(response.body());
        assertThat(raw.get("keys")).hasSize(1);
        JsonNode jwk = raw.get("keys").get(0);
        assertThat(fieldNames(jwk)).containsExactlyInAnyOrder("kid", "kty", "alg", "use", "n", "e");
        assertThat(jwk.get("kty").asText()).isEqualTo("RSA");
        assertThat(jwk.get("alg").asText()).isEqualTo("RS256");
        assertThat(jwk.get("use").asText()).isEqualTo("sig");

        JWKSet set = JWKSet.parse(response.body());
        SignedJWT jwt = SignedJWT.parse(token);
        RSAKey key = (RSAKey) set.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.toRSAPublicKey().getModulus().bitLength()).isEqualTo(3072);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) key.toRSAPublicKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("scarcity-auth");
    }

    // ---------------------------------------------------------------- tester additions

    @Test
    void passwordByteBoundaryAcceptedAndNotTruncatedAtLogin() throws Exception {
        String ascii = unique("b72");
        String ascii72 = "a".repeat(72);
        assertThat(post("/api/auth/register", credentials(ascii, ascii72)).statusCode()).isEqualTo(201);
        assertThat(post("/api/auth/login", credentials(ascii, ascii72)).statusCode()).isEqualTo(200);
        // bcrypt would ignore byte 73; the service must not let a longer password match.
        assertProblem(post("/api/auth/login", credentials(ascii, ascii72 + "X")), 401);

        String multi = unique("e36");
        String eAcute36 = "é".repeat(36); // 72 UTF-8 bytes
        assertThat(post("/api/auth/register", credentials(multi, eAcute36)).statusCode()).isEqualTo(201);
        assertThat(post("/api/auth/login", credentials(multi, eAcute36)).statusCode()).isEqualTo(200);
        assertValidation(credentials(unique("e40"), "é".repeat(40)), "password"); // 80 bytes
    }

    @Test
    void loginIsCaseInsensitiveAndTokenHasContractClaims() throws Exception {
        String username = unique("Frank");
        JsonNode user = json.readTree(post("/api/auth/register", credentials(username, PASSWORD)).body());
        HttpResponse<String> login = post("/api/auth/login", credentials(username.toUpperCase(), PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(login.body());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");

        SignedJWT jwt = SignedJWT.parse(body.get("token").asText());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(tokens.keyId());
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "username", "iat", "exp");
        assertThat(claims.getSubject()).isEqualTo(user.get("id").asText());
        // Stored as entered: the claim carries the registered spelling, not the login spelling.
        assertThat(claims.getStringClaim("username")).isEqualTo(username);
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(3_600_000L);
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(claims.getExpirationTime().toInstant());

        // alg=none with our kid and the same claims must be rejected.
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(("{\"alg\":\"none\",\"kid\":\"" + tokens.keyId() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String unsigned = header + "." + jwt.getParsedParts()[1] + ".";
        assertProblem(get("/api/auth/me", "Bearer " + unsigned), 401);
    }

    // ---------------------------------------------------------------- infra

    @Test
    void flywayCreatedAuthSchemaAndTable() {
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'auth'")
                .query(Integer.class).single()).isEqualTo(1);
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'auth' AND table_name = 'users'
                        """).query(String.class).list();
        assertThat(columns).containsExactlyInAnyOrder("id", "username", "password_hash", "created_at");
        assertThat(jdbc.sql("SELECT count(*) FROM auth.flyway_schema_history WHERE success")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname='auth' AND indexname='users_username_lower_key'")
                .query(String.class).single()).containsIgnoringCase("unique").contains("lower(username)");
    }

    @Test
    void healthProbesAreUpOnTheManagementPortAndOnlyHealthAndPrometheusAreExposed() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> r = getAt(managementPort, path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(json.readTree(r.body()).get("status").asText()).isEqualTo("UP");
        }
        assertThat(getAt(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
        for (String path : List.of("/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/metrics",
                "/actuator/loggers", "/actuator/heapdump", "/actuator/threaddump")) {
            assertThat(getAt(managementPort, path).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void serverPortServesNoActuatorOrMetrics() throws Exception {
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/prometheus", "/actuator/env",
                "/api/auth/actuator/health", "/api/auth/actuator/prometheus", "/api/auth/prometheus",
                "/api/auth/metrics", "/livez", "/readyz", "/metrics", "/prometheus")) {
            HttpResponse<String> r = get(path, null);
            assertThat(r.statusCode()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).doesNotContain("jvm_").doesNotContain("\"UP\"");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertValidation(Object body, String... fields) throws Exception {
        assertValidationAt("/api/auth/register", body, fields);
    }

    private void assertValidationAt(String path, Object body, String... fields) throws Exception {
        HttpResponse<String> r = body instanceof String s ? postRaw(path, s) : post(path, body);
        JsonNode problem = assertProblem(r, 400);
        List<String> reported = problem.get("errors").findValuesAsText("field");
        assertThat(reported).as("errors in %s", r.body()).contains(fields);
    }

    private JsonNode assertProblem(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).as("content type of " + status)
                .hasValueSatisfying(ct -> assertThat(ct).startsWith(PROBLEM_JSON));
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.has("title")).isTrue();
        return body;
    }

    private static Map<String, String> credentials(String username, String password) {
        return Map.of("username", username, "password", password);
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static <T> T join(Future<T> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return postRaw(path, json.writeValueAsString(body));
    }

    private HttpResponse<String> postRaw(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return send(b);
    }

    private HttpResponse<String> getAt(int atPort, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + atPort + path)).GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
