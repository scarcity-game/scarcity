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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.random.RandomGenerator;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

import io.micrometer.tracing.Tracer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scarcity.world.tick.enabled=false",
        "scarcity.resource-changes.listen=true",
        "management.server.port=0"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureObservability // Boot tests switch metrics export off by default
class WorldIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long T0 = 1_000_000_000L;
    /** One orbit-step: arc 0.5 on radius 3.0. */
    static final double ARC = 0.5 / 3.0;

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

    /** A controllable wall clock (epoch millis). */
    static final AtomicLong now = new AtomicLong(T0); // static: JUnit makes a new instance per test
    static final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

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
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state()).isEqualTo(newOrbiterState(8.0, 4.0));
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
    void actionEngineStartsChainsAndPullsBack() throws Exception {
        BlockingQueue<ChangeBatch> got = new LinkedBlockingQueue<>();
        subscriber.addListener(got::add);
        assertThat(subscriber.awaitListening(Duration.ofSeconds(10))).isTrue();
        now.set(T0);
        Ticker t = ticker();

        // First tick: the area gets its clock, the idle orbiter starts its first step at game time 0.
        t.tick();
        ChangeBatch b = got.poll(10, TimeUnit.SECONDS);
        assertThat(b).isNotNull();
        assertThat(b.area()).isEqualTo(WorldIds.AREA);
        assertThat(b.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(b.changes()).extracting(ChangeBatch.Change::id).containsExactly(WorldIds.AREA, WorldIds.ORBITER);
        assertThat(b.changes().get(0).type().name()).isEqualTo("AREA");
        assertThat(b.changes().get(1).type().name()).isEqualTo("ENTITY");
        Resource area = store.get(WorldIds.AREA).orElseThrow();
        assertThat(area.version()).isEqualTo(4);
        assertThat(b.changes().get(0).patch()).isEqualTo(area.state());
        JsonNode clk = area.state().path("clock");
        assertThat(clk.path("gameTime").asDouble()).isEqualTo(0.0);
        assertThat(clk.path("wallTime").asLong()).isEqualTo(T0);
        assertThat(clk.path("rate").asDouble()).isEqualTo(1.0);
        Resource orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(orbiter.version()).isEqualTo(2);
        assertThat(b.changes().get(1).patch()).isEqualTo(orbiter.state());
        assertAction(orbiter, 0.0, 1.0);
        assertThat(orbiter.state().path("action").path("effect").asText()).isEqualTo("end");
        assertThat(orbiter.state().path("action").path("type").asText()).isEqualTo("orbit-step");
        assertPos(orbiter, 8.0, 4.0); // effect at the end: nothing moved yet

        // Nothing due: nothing written, nothing published.
        t.tick();
        now.set(T0 + 999);
        t.tick();
        assertVersion(WorldIds.ORBITER, 2);
        assertVersion(WorldIds.AREA, 4);
        assertThat(got.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // One time unit later: moved by 0.5/3.0 rad, next step starts at the previous end.
        now.set(T0 + 1000);
        t.tick();
        orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(orbiter.version()).isEqualTo(3);
        assertOnCircle(orbiter, 1 * ARC);
        assertAction(orbiter, 1.0, 2.0);
        ChangeBatch b2 = got.poll(10, TimeUnit.SECONDS);
        assertThat(b2.changes()).hasSize(1);
        assertThat(b2.changes().get(0).version()).isEqualTo(3);
        assertThat(b2.changes().get(0).patch()).isEqualTo(orbiter.state());

        // A late tick (game time 2.3): the chained step still starts at exactly 2.0.
        now.set(T0 + 2300);
        t.tick();
        orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(orbiter.version()).isEqualTo(4);
        assertOnCircle(orbiter, 2 * ARC);
        assertAction(orbiter, 2.0, 3.0);
        assertThat(got.poll(10, TimeUnit.SECONDS)).isNotNull();

        // Game time 5.8: steps ending at 3, 4 and 5 run in one tick: one write, one change.
        now.set(T0 + 5800);
        t.tick();
        orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(orbiter.version()).isEqualTo(5);
        assertOnCircle(orbiter, 5 * ARC);
        assertAction(orbiter, 5.0, 6.0);
        ChangeBatch b3 = got.poll(10, TimeUnit.SECONDS);
        assertThat(b3.changes()).hasSize(1);
        assertThat(b3.changes().get(0).version()).isEqualTo(5);

        // 60 s without ticks (lag > 5): the clock is pulled back to the due event (6.0),
        // exactly one step is applied, no burst.
        long late = T0 + 5800 + 60_000;
        now.set(late);
        t.tick();
        orbiter = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(orbiter.version()).isEqualTo(6);
        assertOnCircle(orbiter, 6 * ARC);
        assertAction(orbiter, 6.0, 7.0);
        area = store.get(WorldIds.AREA).orElseThrow();
        assertThat(area.version()).isEqualTo(5);
        assertThat(area.state().path("clock").path("gameTime").asDouble()).isEqualTo(6.0);
        assertThat(area.state().path("clock").path("wallTime").asLong()).isEqualTo(late);
        assertThat(area.state().path("clock").path("rate").asDouble()).isEqualTo(1.0);
        ChangeBatch b4 = got.poll(10, TimeUnit.SECONDS);
        assertThat(b4.changes()).extracting(ChangeBatch.Change::id).containsExactly(WorldIds.AREA, WorldIds.ORBITER);
        assertThat(got.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // Nothing else changed.
        assertVersion(WorldIds.GAME, 2);
        assertVersion(WorldIds.PILLAR, 1);
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().has("action")).isFalse();
    }

    @Test
    @Order(4)
    void aFailedTickRollsBackAndPublishesNothing() throws Exception {
        long before = store.get(WorldIds.ORBITER).orElseThrow().version();
        long areaBefore = store.get(WorldIds.AREA).orElseThrow().version();
        BlockingQueue<ChangeBatch> got = new LinkedBlockingQueue<>();
        subscriber.addListener(got::add);
        ChangePublisher failing = batch -> {
            publisher.publish(batch);
            throw new IllegalStateException("boom");
        };
        long saved = now.get();
        now.set(saved + 1000); // a step is due
        Ticker t = new Ticker(store, failing, tx, clock, tracer, ScriptedRandom.constant(0.0), List.of(new OrbitStep()), 5.0);
        assertThatThrownBy(t::tick).isInstanceOf(IllegalStateException.class);
        assertVersion(WorldIds.ORBITER, before);
        assertVersion(WorldIds.AREA, areaBefore);
        assertThat(got.poll(700, TimeUnit.MILLISECONDS)).isNull();
        now.set(saved);
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

    @Test
    @Order(8)
    void manyStepsKeepTheRadius() {
        Ticker tk = ticker();
        long before = store.get(WorldIds.ORBITER).orElseThrow().version();
        for (int i = 0; i < 1000; i++) {
            now.addAndGet(1000);
            tk.tick();
        }
        Resource o = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(o.version()).isEqualTo(before + 1000);
        double x = o.state().path("position").path("x").asDouble();
        double y = o.state().path("position").path("y").asDouble();
        assertThat(Math.hypot(x - 5.0, y - 4.0)).isCloseTo(3.0, Offset.offset(1e-9));
    }

    @Test
    @Order(9)
    void anOldOrbiterIsUpgradedAtStartupAndThenOrbits() throws Exception {
        // The state the 2026-11-11 deployment has: `orbit`, no `behaviour`, no `action`.
        ObjectNode old = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"orbiter\","
                + "\"position\":{\"x\":5.0,\"y\":7.0},"
                + "\"orbit\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"period\":60.0}}");
        store.updateState(WorldIds.ORBITER, old);
        long v = store.get(WorldIds.ORBITER).orElseThrow().version();

        assertThat(seeder.seed()).isFalse();
        Resource o = store.get(WorldIds.ORBITER).orElseThrow();
        assertThat(o.version()).isEqualTo(v + 1);
        assertThat(o.state()).isEqualTo(newOrbiterState(5.0, 7.0));

        // Running it again changes nothing.
        List<String> before = snapshot();
        assertThat(seeder.seed()).isFalse();
        assertThat(snapshot()).isEqualTo(before);

        // And it orbits: starts a step, finishes it one time unit later (from the top of the circle).
        Ticker tk = ticker();
        tk.tick();
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state().path("action").path("type").asText())
                .isEqualTo("orbit-step");
        now.addAndGet(1000);
        tk.tick();
        Resource after = store.get(WorldIds.ORBITER).orElseThrow();
        double angle = Math.PI / 2 + ARC;
        assertThat(after.state().path("position").path("x").asDouble())
                .isCloseTo(5.0 + 3.0 * Math.cos(angle), Offset.offset(1e-9));
        assertThat(after.state().path("position").path("y").asDouble())
                .isCloseTo(4.0 + 3.0 * Math.sin(angle), Offset.offset(1e-9));
    }

    /** Test-only action type whose effect happens at the start: counts starts. */
    static class Poke implements ActionType {
        @Override public String name() { return "poke"; }
        @Override public Effect effect() { return Effect.START; }
        @Override public double duration(JsonNode params, ObjectNode state) { return 2.0; }
        @Override public void apply(JsonNode params, ObjectNode state) {
            state.put("count", state.path("count").asInt() + 1);
        }
    }

    @Test
    @Order(10)
    void aStartEffectChangesStateAtStartAndNothingAtEnd() throws Exception {
        Ticker tk = new Ticker(store, publisher, tx, clock, tracer, ScriptedRandom.constant(0.0), List.of(new OrbitStep(), new Poke()), 5.0);
        ObjectNode state = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"poker\","
                + "\"actions\":[\"poke\"],\"behaviour\":{\"kind\":\"repeat\","
                + "\"action\":{\"type\":\"poke\",\"params\":{}}}}");
        Resource r = Resource.newResource(ResourceType.ENTITY, state);
        tx.executeWithoutResult(s -> store.create(r, WorldIds.AREA));
        try {
            tk.tick();
            Resource p = store.get(r.id()).orElseThrow();
            assertThat(p.state().path("count").asInt()).isEqualTo(1); // changed at the start
            assertThat(p.state().path("action").path("effect").asText()).isEqualTo("start");
            double start = p.state().path("action").path("start").asDouble();
            assertThat(p.state().path("action").path("end").asDouble()).isEqualTo(start + 2.0);

            // Drop the behaviour: when the action ends, nothing more happens.
            ObjectNode noBehaviour = p.state().deepCopy();
            noBehaviour.remove("behaviour");
            store.updateState(r.id(), noBehaviour);
            now.addAndGet(2000);
            tk.tick();
            Resource ended = store.get(r.id()).orElseThrow();
            assertThat(ended.state().has("action")).isFalse();
            assertThat(ended.state().path("count").asInt()).isEqualTo(1);
        } finally {
            tx.executeWithoutResult(s -> store.delete(r.id()));
        }
    }

    @Test
    @Order(11)
    void aRepeatOrbiterWithAnActionInProgressIsUpgradedOnce() throws Exception {
        ObjectNode old = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"orbiter\",\"position\":{\"x\":5.0,\"y\":7.0},\"speed\":0.5,\"actions\":[\"orbit-step\"],\"behaviour\":{\"kind\":\"repeat\",\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5}}},\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5},\"start\":1000000.0,\"end\":1000001.0,\"effect\":\"end\"}}" );
        store.updateState(WorldIds.ORBITER, old);
        long v = store.get(WorldIds.ORBITER).orElseThrow().version();
        try {
            assertThat(seeder.seed()).isFalse();
            Resource o = store.get(WorldIds.ORBITER).orElseThrow();
            assertThat(o.version()).isEqualTo(v + 1);
            ObjectNode expected = (ObjectNode) newOrbiterState(5.0, 7.0).deepCopy();
            expected.set("action", old.get("action"));
            assertThat(o.state()).isEqualTo(expected);
            List<String> before = snapshot();
            assertThat(seeder.seed()).isFalse();
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            store.updateState(WorldIds.ORBITER, (ObjectNode) newOrbiterState(8.0, 4.0));
        }
    }

    @Test
    @Order(12)
    void randomBehaviourDrawsAndRecordsTheDistribution() throws Exception {
        ObjectNode saved = parkOrbiter();
        ScriptedRandom g = new ScriptedRandom(0.25, 0.75, 0.0);
        Resource r = spawn(RANDOM_BEHAVIOUR, "[\"orbit-step\",\"wait\"]");
        try {
            Ticker tk = ticker(g);
            tk.tick();
            JsonNode a1 = store.get(r.id()).orElseThrow().state().path("action");
            assertThat(a1.path("type").asText()).isEqualTo("orbit-step");
            assertThat(a1.path("effect").asText()).isEqualTo("end");
            assertThat(a1.path("end").asDouble()).isCloseTo(a1.path("start").asDouble() + 1.0, Offset.offset(1e-9));
            JsonNode d1 = a1.path("draw");
            assertThat(d1.path("dist").asText()).isEqualTo("categorical");
            assertThat(d1.path("weights")).hasSize(2);
            assertThat(d1.path("weights").get(0).asDouble()).isEqualTo(1.0);
            assertThat(d1.path("weights").get(1).asDouble()).isEqualTo(1.0);
            assertThat(d1.path("u").asDouble()).isEqualTo(0.25);
            assertThat(d1.path("value").asInt()).isEqualTo(0);
            assertPos(store.get(r.id()).orElseThrow(), 8.0, 4.0);

            now.addAndGet(1000);
            tk.tick();
            Resource r2 = store.get(r.id()).orElseThrow();
            JsonNode a2 = r2.state().path("action");
            assertOnCircle(r2, ARC);
            assertThat(a2.path("type").asText()).isEqualTo("wait");
            assertThat(a2.path("effect").asText()).isEqualTo("none");
            assertThat(a2.path("start").asDouble()).isEqualTo(a1.path("end").asDouble());
            assertThat(a2.path("end").asDouble()).isCloseTo(a2.path("start").asDouble() + 1.0, Offset.offset(1e-9));
            assertThat(a2.path("draw").path("u").asDouble()).isEqualTo(0.75);
            assertThat(a2.path("draw").path("value").asInt()).isEqualTo(1);

            now.addAndGet(1000);
            tk.tick();
            Resource r3 = store.get(r.id()).orElseThrow();
            assertOnCircle(r3, ARC); // the wait changed nothing
            JsonNode a3 = r3.state().path("action");
            assertThat(a3.path("start").asDouble()).isEqualTo(a2.path("end").asDouble());
            assertThat(a3.path("draw").path("u").asDouble()).isEqualTo(0.0);
            assertThat(a3.path("draw").path("value").asInt()).isEqualTo(0);
            assertThat(g.draws()).isEqualTo(3);
        } finally {
            despawn(r);
            unparkOrbiter(saved);
        }
    }

    @Test
    @Order(13)
    void anInvalidRandomBehaviourStartsNothingAndConsumesNoDraw() throws Exception {
        ObjectNode saved = parkOrbiter();
        ScriptedRandom g = new ScriptedRandom(0.5, 0.5, 0.5);
        Ticker tk = ticker(g);
        String[][] cases = {
            {"{\"kind\":\"random\",\"choices\":[]}", "[\"orbit-step\",\"wait\"]"},
            {"{\"kind\":\"random\"}", "[\"orbit-step\",\"wait\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":0.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}", "[\"wait\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":-1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}", "[\"wait\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":\"1\",\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}", "[\"wait\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"dance\",\"params\":{}}}]}", "[\"dance\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}", "[\"orbit-step\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":0.0}}}]}", "[\"wait\"]"},
            {"{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}},{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":-1.0}}}]}", "[\"wait\"]"}
        };
        try {
            for (String[] c : cases) {
                Resource r = spawn(c[0], c[1]);
                try {
                    tk.tick();
                    Resource after = store.get(r.id()).orElseThrow();
                    assertThat(after.state().has("action")).as(c[0]).isFalse();
                    assertThat(after.version()).as(c[0]).isEqualTo(1L);
                } finally {
                    despawn(r);
                }
            }
            assertThat(g.draws()).isEqualTo(0);
        } finally {
            unparkOrbiter(saved);
        }
    }

    @Test
    @Order(14)
    void threeChainedStartsInOneTickDrawThreeTimesAndWriteOnce() throws Exception {
        ScriptedRandom g = new ScriptedRandom(0.0, 0.9, 0.0, 0.9);
        Ticker tk = ticker(g);
        ObjectNode saved = parkOrbiter();
        Resource r = spawn(RANDOM_BEHAVIOUR, "[\"orbit-step\",\"wait\"]");
        try {
            tk.tick();
            assertThat(g.draws()).isEqualTo(1);
            long v = store.get(r.id()).orElseThrow().version();
            now.addAndGet(3000);
            tk.tick();
            assertThat(g.draws()).isEqualTo(4);
            assertThat(store.get(r.id()).orElseThrow().version()).isEqualTo(v + 1);
        } finally {
            despawn(r);
            unparkOrbiter(saved);
        }
    }

    @Test
    @Order(15)
    void aRepeatBehaviourStillWorksAndHasNoDraw() throws Exception {
        String repeat = "{\"kind\":\"repeat\",\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5}}}";
        ObjectNode saved = parkOrbiter();
        Resource r = spawn(repeat, "[\"orbit-step\"]");
        try {
            ScriptedRandom g = new ScriptedRandom(0.5);
            ticker(g).tick();
            JsonNode a = store.get(r.id()).orElseThrow().state().path("action");
            assertThat(a.path("type").asText()).isEqualTo("orbit-step");
            assertThat(a.has("draw")).isFalse();
            assertThat(g.draws()).isEqualTo(0);
        } finally {
            despawn(r);
            unparkOrbiter(saved);
        }
    }

    @Test
    @Order(16)
    void aSeededGeneratorPicksBothChoicesAboutEvenlyAndKeepsTheRadius() throws Exception {
        ObjectNode saved = parkOrbiter();
        Resource r = spawn(RANDOM_BEHAVIOUR, "[\"orbit-step\",\"wait\"]");
        try {
            Ticker tk = ticker(new java.util.SplittableRandom(42));
            int[] counts = new int[2];
            for (int i = 0; i < 2000; i++) {
                now.addAndGet(1000);
                tk.tick();
                int choice = store.get(r.id()).orElseThrow().state().path("action")
                        .path("draw").path("value").asInt();
                counts[choice]++;
            }
            assertThat(counts[0]).isPositive();
            assertThat(counts[1]).isPositive();
            assertThat((double) counts[0] / 2000).isBetween(0.45, 0.55);
            JsonNode pos = store.get(r.id()).orElseThrow().state().path("position");
            assertThat(Math.hypot(pos.path("x").asDouble() - 5.0, pos.path("y").asDouble() - 4.0))
                    .isCloseTo(3.0, Offset.offset(1e-9));
        } finally {
            despawn(r);
            unparkOrbiter(saved);
        }
    }

    private static final String RANDOM_BEHAVIOUR = "{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5}}},{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}";

    /** A ticker on the test clock with both action types and the given generator. */
    private Ticker ticker(RandomGenerator g) {
        return new Ticker(store, publisher, tx, clock, tracer, g, List.of(new OrbitStep(), new Wait()), 5.0);
    }

    /** An extra mobile entity in the test area with the given behaviour and actions (JSON texts). */
    private Resource spawn(String behaviourJson, String actionsJson) throws Exception {
        ObjectNode state = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"rnd\","
                + "\"position\":{\"x\":8.0,\"y\":4.0},\"speed\":0.5,\"actions\":" + actionsJson
                + ",\"behaviour\":" + behaviourJson + "}");
        Resource r = Resource.newResource(ResourceType.ENTITY, state);
        tx.executeWithoutResult(s -> store.create(r, WorldIds.AREA));
        return r;
    }

    private void despawn(Resource r) {
        tx.executeWithoutResult(s -> store.delete(r.id()));
    }

    /** Takes the behaviour and action off the orbiter so it draws nothing; returns its saved state. */
    private ObjectNode parkOrbiter() {
        ObjectNode saved = store.get(WorldIds.ORBITER).orElseThrow().state().deepCopy();
        ObjectNode parked = saved.deepCopy();
        parked.remove("behaviour");
        parked.remove("action");
        store.updateState(WorldIds.ORBITER, parked);
        return saved;
    }

    private void unparkOrbiter(ObjectNode saved) {
        store.updateState(WorldIds.ORBITER, saved);
    }

    /** A ticker on the test clock whose generator always returns 0.0 (the first choice: orbit-step). */
    private Ticker ticker() {
        return ticker(ScriptedRandom.constant(0.0));
    }

    private static JsonNode newOrbiterState(double x, double y) throws Exception {
        return JSON.readTree("{\"kind\":\"mobile\",\"name\":\"orbiter\",\"position\":{\"x\":" + x + ",\"y\":" + y
                + "},\"speed\":0.5,\"actions\":[\"orbit-step\",\"wait\"],"
                + "\"behaviour\":{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5}}},{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}}");
    }

    private void assertVersion(String id, long version) {
        assertThat(store.get(id).orElseThrow().version()).as(id).isEqualTo(version);
    }

    private static void assertPos(Resource r, double x, double y) {
        assertThat(r.state().path("position").path("x").asDouble()).isCloseTo(x, Offset.offset(1e-9));
        assertThat(r.state().path("position").path("y").asDouble()).isCloseTo(y, Offset.offset(1e-9));
    }

    private static void assertAction(Resource r, double start, double end) {
        JsonNode a = r.state().path("action");
        assertThat(a.path("start").asDouble()).isEqualTo(start); // exact: chained at the previous end
        assertThat(a.path("end").asDouble()).isEqualTo(end);
    }

    /** The orbiter is {@code angle} rad counter-clockwise from (8,4) on the circle of radius 3 around (5,4). */
    private static void assertOnCircle(Resource r, double angle) {
        double x = r.state().path("position").path("x").asDouble();
        double y = r.state().path("position").path("y").asDouble();
        assertThat(Math.hypot(x - 5.0, y - 4.0)).isCloseTo(3.0, Offset.offset(1e-9));
        assertThat(x).isCloseTo(5.0 + 3.0 * Math.cos(angle), Offset.offset(1e-9));
        assertThat(y).isCloseTo(4.0 + 3.0 * Math.sin(angle), Offset.offset(1e-9));
    }

    private List<String> snapshot() {
        return jdbc.queryForList(
                "SELECT id || '@' || version || '@' || updated_at || state FROM resources ORDER BY id", String.class);
    }
}
