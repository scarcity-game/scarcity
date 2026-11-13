package com.pessimisticit.scarcity.world;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;

import io.micrometer.tracing.Tracer;

/**
 * Shared set-up of the area log tests: the same Spring context as {@link WorldIntegrationTest}
 * (tick loop off), a controllable wall clock, and a freshly seeded world with an empty log before
 * every test. The DB is shared with other classes, so it is left freshly seeded afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scarcity.world.tick.enabled=false",
        "scarcity.resource-changes.listen=true",
        "management.server.port=0"})
@AutoConfigureObservability
abstract class LogTestBase {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long T0 = 1_000_000_000L;
    /** One orbit-step: arc 0.5 on radius 3.0. */
    static final double ARC = 0.5 / 3.0;

    static final AtomicLong now = new AtomicLong(T0);
    static final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    static final String REPEAT_ORBIT = """
            {"kind":"repeat","action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":3.0,"arc":0.5}}}
            """;

    private static JdbcTemplate sharedJdbc;
    private static Seeder sharedSeeder;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestSupport.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestSupport.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestSupport.POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestSupport::jwksUri);
    }

    @Autowired ResourceStore store;
    @Autowired ChangePublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Tracer tracer;
    @Autowired Seeder seeder;
    @Autowired AreaLog areaLog;
    @LocalServerPort int port;

    @BeforeEach
    void freshWorld() {
        sharedJdbc = jdbc;
        sharedSeeder = seeder;
        wipe(jdbc);
        seeder.seed();
        now.set(T0);
    }

    @AfterAll
    static void leaveSeeded() {
        if (sharedJdbc != null) {
            wipe(sharedJdbc);
            sharedSeeder.seed();
        }
    }

    private static void wipe(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM area_log");
        jdbc.update("DELETE FROM area_log_head");
        for (String prefix : List.of("item:%", "entity:%", "area:%", "game:%")) {
            jdbc.update("DELETE FROM resources WHERE id LIKE ?", prefix);
        }
    }

    Ticker ticker(RandomGenerator g) {
        return ticker(g, publisher);
    }

    Ticker ticker(RandomGenerator g, ChangePublisher pub) {
        return new Ticker(store, pub, tx, clock, tracer, g, List.of(new OrbitStep(), new Wait()), 5.0, areaLog);
    }

    /** Replaces the orbiter's behaviour (and its in-progress action, if any) before the log starts. */
    void setOrbiter(String behaviourJson, ObjectNode action) throws Exception {
        ObjectNode state = store.get(WorldIds.ORBITER).orElseThrow().state().deepCopy();
        state.set("behaviour", JSON.readTree(behaviourJson));
        if (action != null) {
            state.set("action", action);
        }
        store.updateState(WorldIds.ORBITER, state);
    }

    long version(String id) {
        return store.get(id).orElseThrow().version();
    }

    /** The whole area log, ascending. */
    List<JsonNode> entries() {
        List<JsonNode> out = new ArrayList<>();
        long after = 0;
        while (true) {
            JsonNode page = areaLog.read(WorldIds.AREA, after, 500, null);
            for (JsonNode e : page.path("entries")) {
                out.add(e);
                after = e.path("seq").asLong();
            }
            if (page.path("entries").size() < 500) {
                return out;
            }
        }
    }

    List<JsonNode> entriesAfter(long seq) {
        return entries().stream().filter(e -> e.path("seq").asLong() > seq).toList();
    }

    static List<String> kinds(List<JsonNode> entries) {
        return entries.stream().map(e -> e.path("kind").asText()).toList();
    }

    /** The version a change of {@code id} in this entry carries. */
    static long versionOf(JsonNode entry, String id) {
        for (JsonNode c : entry.path("changes")) {
            if (c.path("id").asText().equals(id)) {
                return c.path("version").asLong();
            }
        }
        throw new AssertionError("entry " + entry.path("seq") + " has no change for " + id);
    }
}
