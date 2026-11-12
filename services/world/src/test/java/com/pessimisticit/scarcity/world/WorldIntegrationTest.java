package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;

import io.micrometer.tracing.Tracer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scarcity.world.tick.enabled=false",
        "scarcity.resource-changes.listen=true",
        "management.server.port=0"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureObservability // Boot tests switch metrics export off by default
class WorldIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestSupport.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestSupport.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestSupport.POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestSupport::jwksUri);
    }

    @Autowired ResourceStore store;
    @Autowired ChangePublisher publisher;
    @Autowired ChangeSubscriber subscriber;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Tracer tracer;
    @Autowired Seeder seeder;
    @LocalServerPort int port;
    @LocalManagementPort int mgmtPort;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(int p, String path, String token, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    @Test
    @Order(1)
    void flywayCreatesWorldSchemaAndSeedWritesTheFourResources() throws Exception {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='world' AND table_name='resources'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources", Integer.class)).isEqualTo(4);

        // Creating a child bumps its parent (library version rule): game 2, area 3.
        assertVersion(WorldIds.GAME, 2);
        assertVersion(WorldIds.AREA, 3);
        assertVersion(WorldIds.PILLAR, 1);
        assertVersion(WorldIds.ORBITER, 1);

        Resource area = store.get(WorldIds.AREA).orElseThrow();
        assertThat(area.children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);
        assertThat(area.state().toString()).isEqualTo("{\"name\":\"Test room\",\"width\":10.0,\"height\":8.0}");
        assertThat(store.get(WorldIds.GAME).orElseThrow().children()).containsExactly(WorldIds.AREA);
        assertThat(store.get(WorldIds.GAME).orElseThrow().state().toString()).isEqualTo("{\"name\":\"Test area\"}");
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().toString())
                .isEqualTo("{\"kind\":\"fixed\",\"name\":\"pillar\",\"position\":{\"x\":5.0,\"y\":4.0}}");
        // jsonb doesn't keep key order, so compare as trees.
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state()).isEqualTo(JSON.readTree(
                "{\"kind\":\"mobile\",\"name\":\"orbiter\",\"position\":{\"x\":8.0,\"y\":4.0},"
                        + "\"orbit\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"period\":60.0}}"));
        assertThat(jdbc.queryForObject("SELECT area_id FROM resources WHERE id = ?", String.class, WorldIds.ORBITER))
                .isEqualTo(WorldIds.AREA);
    }

    @Test
    @Order(2)
    void seedingAgainChangesNothing() {
        List<String> before = snapshot();
        assertThat(seeder.seed()).isFalse();
        assertThat(seeder.seed()).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Order(3)
    void tickMovesOnlyTheOrbiterAndPublishesOneBatch() throws Exception {
        BlockingQueue<ChangeBatch> got = new LinkedBlockingQueue<>();
        subscriber.addListener(got::add);
        assertThat(subscriber.awaitListening(Duration.ofSeconds(10))).isTrue();

        // t = 0: angle 0 -> (8, 4).
        ticker(0).tick();
        ChangeBatch b = got.poll(10, TimeUnit.SECONDS);
        assertThat(b).isNotNull();
        assertThat(b.area()).isEqualTo(WorldIds.AREA);
        assertThat(b.changes()).hasSize(1);
        assertThat(b.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        ChangeBatch.Change c = b.changes().get(0);
        assertThat(c.id()).isEqualTo(WorldIds.ORBITER);
        assertThat(c.type().name()).isEqualTo("ENTITY");
        assertThat(c.version()).isEqualTo(2);
        Resource orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(c.patch()).isEqualTo(orbiter.state());
        assertThat(orbiter.version()).isEqualTo(2);
        assertPos(orbiter, 8.0, 4.0);
        assertThat(orbiter.state().path("kind").asText()).isEqualTo("mobile");
        assertThat(orbiter.state().path("orbit").path("radius").asDouble()).isEqualTo(3.0);

        // Quarter period (15 s): angle pi/2 -> (5, 7).
        ticker(15_000).tick();
        ChangeBatch b2 = got.poll(10, TimeUnit.SECONDS);
        assertThat(b2).isNotNull();
        assertThat(b2.changes().get(0).version()).isEqualTo(3);
        assertPos(store.get(WorldIds.ORBITER).orElseThrow(), 5.0, 7.0);
        // Half and three quarters; the period wraps (45 s + 60 s is the same angle as 45 s).
        ticker(30_000).tick();
        assertPos(store.get(WorldIds.ORBITER).orElseThrow(), 2.0, 4.0);
        ticker(45_000 + 60_000).tick();
        assertPos(store.get(WorldIds.ORBITER).orElseThrow(), 5.0, 1.0);

        // Nothing else changed, and exactly one notification per tick.
        assertVersion(WorldIds.GAME, 2);
        assertVersion(WorldIds.AREA, 3);
        assertVersion(WorldIds.PILLAR, 1);
        assertVersion(WorldIds.ORBITER, 5);
        assertThat(got.poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(got.poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(got.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @Order(4)
    void aFailedTickRollsBackAndPublishesNothing() throws Exception {
        long before = store.get(WorldIds.ORBITER).orElseThrow().version();
        BlockingQueue<ChangeBatch> got = new LinkedBlockingQueue<>();
        subscriber.addListener(got::add);
        ChangePublisher failing = batch -> {
            publisher.publish(batch);
            throw new IllegalStateException("boom");
        };
        Ticker t = new Ticker(store, failing, tx, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), tracer);
        assertThatThrownBy(t::tick).isInstanceOf(IllegalStateException.class);
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().version()).isEqualTo(before);
        assertThat(got.poll(700, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @Order(5)
    void resourceApiRequiresAJwt() throws Exception {
        assertThat(get(port, "/api/world/resources?type=game", null).statusCode()).isEqualTo(401);
        HttpResponse<String> r = get(port, "/api/world/resources?type=game", "not.a.jwt");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEmpty();
        // Signed by a key the JWKS doesn't have, wrong issuer, expired.
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.OTHER_KEY, "scarcity-auth", 60_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.KEY, "someone-else", 60_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.KEY, "scarcity-auth", -120_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources/" + WorldIds.ORBITER, null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/anything/else", null).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(6)
    void allThreeResourceOperationsWorkWithAToken() throws Exception {
        String tok = TestSupport.validToken();
        JsonNode games = json(get(port, "/api/world/resources?type=game", tok));
        assertThat(games.path("items")).hasSize(1);
        assertThat(games.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.GAME);

        JsonNode areas = json(get(port, "/api/world/resources/" + WorldIds.GAME + "/children?type=area", tok));
        assertThat(areas.path("items")).hasSize(1);
        assertThat(areas.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.AREA);

        JsonNode ents = json(get(port, "/api/world/resources/" + WorldIds.AREA + "/children", tok));
        assertThat(ents.path("items")).hasSize(2);
        assertThat(ents.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.PILLAR);
        assertThat(ents.path("items").get(1).path("id").asText()).isEqualTo(WorldIds.ORBITER);

        HttpResponse<String> one = get(port, "/api/world/resources/" + WorldIds.ORBITER, tok);
        assertThat(one.statusCode()).isEqualTo(200);
        String etag = one.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + json(one).path("version").asLong() + "\"");
        HttpResponse<String> again = get(port, "/api/world/resources/" + WorldIds.ORBITER, tok,
                "If-None-Match", etag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(get(port, "/api/world/resources/entity:00000000-0000-4000-8000-0000000000ff", tok)
                .statusCode()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void actuatorOnManagementPortIsOpen() throws Exception {
        assertThat(mgmtPort).isNotEqualTo(port);
        assertThat(get(mgmtPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get(mgmtPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        HttpResponse<String> p = get(mgmtPort, "/actuator/prometheus", null);
        assertThat(p.statusCode()).isEqualTo(200);
        assertThat(p.body()).contains("jvm_memory");
        // Actuator is not served on the API port.
        assertThat(get(port, "/actuator/health", null).statusCode()).isIn(401, 404);
    }

    private Ticker ticker(long epochMillis) {
        return new Ticker(store, publisher, tx, Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC),
                tracer);
    }

    private void assertVersion(String id, long version) {
        assertThat(store.get(id).orElseThrow().version()).as(id).isEqualTo(version);
    }

    private static void assertPos(Resource r, double x, double y) {
        assertThat(r.state().path("position").path("x").asDouble()).isCloseTo(x, Offset.offset(1e-9));
        assertThat(r.state().path("position").path("y").asDouble()).isCloseTo(y, Offset.offset(1e-9));
    }

    private List<String> snapshot() {
        return jdbc.queryForList(
                "SELECT id || '@' || version || '@' || updated_at || state FROM resources ORDER BY id", String.class);
    }
}
