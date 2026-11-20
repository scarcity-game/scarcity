package com.pessimisticit.scarcity.world;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.WorldJson;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.seed.Seeder;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The service against a real Postgres and a JWKS endpoint (TestSupport). One
 * Spring context for every subclass; each test starts from a freshly seeded
 * test area. Engine and database rules are the libs' tests; these are the
 * HTTP and wiring layer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scarcity.resource-changes.listen=true",
        "management.server.port=0"})
@AutoConfigureMetrics
@AutoConfigureTracing // Boot tests switch metrics export off by default
abstract class WorldTest {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The sub of TestSupport's tokens. */
    static final String SUB = "00000000-0000-4000-8000-0000000000aa";
    /** A user-controlled entity some tests insert; the test area has none yet. Before the orbiter by id. */
    static final String HERO = "entity:00000000-0000-4000-8000-000000000000";

    /** orbital's service token; the orbiter names orbital as its controller. */
    static final String ORBITAL = TestSupport.serviceToken("orbital");
    /** The service token of a principal that controls nothing. */
    static final String STRANGER = TestSupport.serviceToken("stranger");

    /** Every batch the service published, in order; cleared before each test. */
    static final ConcurrentLinkedQueue<ChangeBatch> BATCHES = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestSupport.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestSupport.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestSupport.POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestSupport::jwksUri);
    }

    @Autowired ResourceStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Seeder seeder;
    @Autowired Intents intents;
    @Autowired ChangeSubscriber subscriber;
    @Autowired MeterRegistry meters;
    @LocalServerPort int port;
    @LocalManagementPort int mgmtPort;

    final HttpClient http;

    WorldTest() {
        this.http = HttpClient.newHttpClient();
    }

    @BeforeEach
    void freshTestArea() throws Exception {
        if (LISTENING.compareAndSet(false, true)) {
            subscriber.addListener(BATCHES::add);
        }
        if (!subscriber.awaitListening(Duration.ofSeconds(10))) {
            throw new IllegalStateException("the subscriber isn't listening");
        }
        jdbc.execute("TRUNCATE resources, area_log, area_log_head");
        seeder.seed();
        BATCHES.clear();
    }

    /**
     * Adds {@link #HERO}: idle, can step and wait, controlled by {@link #SUB}. Its id is below the
     * orbiter's, so with both idle from 0 the hero is up and the orbiter pending
     * (docs/actions.md, "Turns").
     */
    void insertHero() throws Exception {
        ObjectNode state = (ObjectNode) JSON.readTree("{\"resourceKind\":\"entity\",\"entityKind\":\"player\","
                + "\"prototype\":\"hero\",\"name\":\"hero\","
                + "\"position\":{\"x\":2.0,\"y\":2.0},\"mass\":70000.0,\"speed\":1.0,\"radius\":0.3,\"actions\":[\"step\",\"wait\"],"
                + "\"controller\":{\"kind\":\"user\",\"sub\":\"" + SUB + "\"}}");
        Entity hero = WorldJson.fromJson(state, Entity.class).withId(HERO);
        tx.executeWithoutResult(s -> store.create(hero, WorldIds.AREA));
    }

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

    /** {@code POST /api/world/intents} with the given headers (name, value, ...) and no token. */
    HttpResponse<String> postIntentWith(String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/intents"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postIntent(String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/intents"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String waitIntent(String entity, double duration) {
        return "{\"entity\":\"" + entity + "\",\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":" + duration
                + "}}}";
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    Resource resource(String id) {
        return store.get(id).orElseThrow();
    }

    Area area() {
        return (Area) resource(WorldIds.AREA);
    }

    Entity entity(String id) {
        return (Entity) resource(id);
    }
}
