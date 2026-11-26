package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * world dispatching turns to an intelligence service (docs/actions.md, "Intelligence services";
 * #373). The orbiter's controller, {@code orbital}, is registered here as an intelligence service
 * whose {@code /turn} is a stub, as is auth's token exchange. The outbox's own rules (claiming,
 * two dispatchers, leases) are libs/world-db's TurnOutboxTest.
 */
class TurnDispatchTest extends WorldTest {

    private static final HttpServer STUB;
    /** What the stub's {@code /turn} does next; the default answers 503. */
    private static final AtomicReference<Turns> TURNS = new AtomicReference<>();
    /** The bodies {@code /turn} was posted, and their Authorization headers. */
    private static final ConcurrentLinkedQueue<JsonNode> ASKED = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> AUTHORIZATIONS = new ConcurrentLinkedQueue<>();
    private static final String WAIT = "{\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}},"
            + "\"reported\":{\"draw\":{\"dist\":\"categorical\",\"weights\":[1.0],\"u\":0.5,\"value\":0}}}";

    /** The stub's answer to one turn: a status and a body. */
    @FunctionalInterface
    interface Turns {
        Answer next(JsonNode asked) throws Exception;
    }

    record Answer(int status, String body) {
    }

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/api/auth/service-token", ex -> reply(ex, 200,
                    "{\"token\":\"" + TestSupport.serviceToken("world") + "\",\"expiresIn\":900}"));
            STUB.createContext("/turn", TurnDispatchTest::turn);
            STUB.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        down();
    }

    @DynamicPropertySource
    static void intelligence(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        r.add("world.intelligence.services.orbital.base-url", () -> base);
        r.add("world.intelligence.services.orbital.range", () -> "8.0");
        r.add("world.intelligence.auth-url", () -> base);
        r.add("world.intelligence.api-key", () -> "test-key");
        r.add("world.intelligence.backoff", () -> "10ms");
        r.add("world.intelligence.backoff-max", () -> "40ms");
        r.add("world.intelligence.poll", () -> "50ms");
        r.add("world.intelligence.timeout", () -> "2s");
        r.add("world.intelligence.lease", () -> "5s");
    }

    @Autowired AreaRepository areaRepository;
    @Autowired QueueSweep queueSweep;

    @AfterEach
    void stopDispatching() {
        down();
        jdbc.execute("TRUNCATE turn_outbox");
        ASKED.clear();
        AUTHORIZATIONS.clear();
    }

    @Test
    void theOrbitersTurnIsAskedOfItsServiceAndTheAnswerApplied() throws Exception {
        answers(List.of(new Answer(200, WAIT)));
        offerTheOrbitersTurn();

        await(() -> intents() == 1);
        assertThat(area().time()).isEqualTo(1.0);
        JsonNode asked = ASKED.peek();
        assertThat(asked.path("entity").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(asked.path("self").path("id").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(asked.path("area").asText()).isEqualTo(WorldIds.AREA);
        assertThat(asked.path("range").asDouble()).isEqualTo(8.0);
        assertThat(asked.path("version").asLong()).isPositive();
        assertThat(asked.path("cells").isArray()).isTrue();
        // world's own service token, a JWT from the exchange.
        assertThat(AUTHORIZATIONS.peek()).startsWith("Bearer ey");
        // Logged as the orbital's intent, with its reported draw.
        JsonNode entry = JSON.readTree(jdbc.queryForObject(
                "SELECT body::text FROM area_log WHERE area_id = ? AND kind = 'intent'", String.class, WorldIds.AREA));
        assertThat(entry.path("controller").path("principal").asText()).isEqualTo("orbital");
        assertThat(entry.path("reported").path("draw").path("dist").asText()).isEqualTo("categorical");
    }

    @Test
    void aServiceDownForAWhileGivesExactlyOneIntentOnceItIsUp() throws Exception {
        double failed = dispatched("failed");
        answers(List.of(new Answer(503, "down"), new Answer(500, "boom"), new Answer(200, "not json"),
                new Answer(200, WAIT)));
        offerTheOrbitersTurn();

        await(() -> intents() == 1);
        assertThat(ASKED).hasSizeGreaterThanOrEqualTo(4);
        assertThat(area().time()).isEqualTo(1.0);
        assertThat(dispatched("failed") - failed).isGreaterThanOrEqualTo(3);
    }

    @Test
    void anAnswerToATurnThatIsNoLongerUpIsDropped() throws Exception {
        double stale = dispatched("stale");
        // While the service thinks, the orbiter's turn is taken by an intent posted directly.
        TURNS.set(asked -> {
            TURNS.set(TurnDispatchTest::unavailable);
            assertThat(postIntent(waitIntent(WorldIds.ORBITER, 2.0), ORBITAL).statusCode()).isEqualTo(200);
            return new Answer(200, WAIT);
        });
        offerTheOrbitersTurn();

        await(() -> dispatched("stale") > stale);
        assertThat(intents()).isEqualTo(1);
        assertThat(area().time()).isEqualTo(2.0);
    }

    @Test
    void aTurnClaimedByADispatcherThatDiedIsDispatchedOnceItsLeaseEnds() throws Exception {
        answers(List.of(new Answer(200, WAIT)));
        // Another pod claimed the orbiter's turn and died: its lease runs for an hour.
        jdbc.update("INSERT INTO turn_outbox (area_id, entity, since, principal, attempts, next_at) "
                + "VALUES (?, ?, 0.0, 'orbital', 1, now() + interval '1 hour')", WorldIds.AREA, WorldIds.ORBITER);
        Thread.sleep(200);
        assertThat(ASKED).isEmpty();

        jdbc.update("UPDATE turn_outbox SET next_at = now() WHERE area_id = ?", WorldIds.AREA);
        await(() -> intents() == 1);
        assertThat(area().time()).isEqualTo(1.0);
    }

    @Test
    void anAreaWhoseTurnIsNotAnIntelligencesHasNoRow() throws Exception {
        offerTheOrbitersTurn();
        insertHero();
        // The startup sweep writes the queue: the hero is up (its id is below the orbiter's), a user's
        // turn, so the commit clears the orbiter's row.
        queueSweep.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_outbox", Integer.class)).isZero();
    }

    private void offerTheOrbitersTurn() {
        tx.executeWithoutResult(s -> areaRepository.offerTurn(WorldIds.AREA, area()));
    }

    private int intents() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM area_log WHERE area_id = ? AND kind = 'intent'",
                Integer.class, WorldIds.AREA);
        return n == null ? 0 : n;
    }

    private double dispatched(String outcome) {
        var counter = meters.find("world.turns.dispatched").tag("principal", "orbital").tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** The stub answers these in turn, then 503 to everything. */
    private static void answers(List<Answer> answers) {
        ConcurrentLinkedQueue<Answer> queue = new ConcurrentLinkedQueue<>(answers);
        TURNS.set(asked -> {
            Answer a = queue.poll();
            return a == null ? unavailable(asked) : a;
        });
    }

    private static void down() {
        TURNS.set(TurnDispatchTest::unavailable);
    }

    private static Answer unavailable(JsonNode asked) {
        return new Answer(503, "{\"error\":\"down\"}");
    }

    private static void turn(HttpExchange ex) throws IOException {
        try {
            JsonNode asked = JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ASKED.add(asked);
            AUTHORIZATIONS.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            Answer a = TURNS.get().next(asked);
            reply(ex, a.status(), a.body());
        } catch (Exception e) {
            reply(ex, 500, String.valueOf(e));
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(20);
        }
    }
}
