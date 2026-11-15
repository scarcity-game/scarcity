package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.orbital.StubWorld.Answer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlayerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WAIT = "\"type\":\"wait\"";
    private static final String BUSY_ORBITER = StubWorld.IDLE_ORBITER.replace("\"controller\"",
            "\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0},\"start\":0.0,\"end\":1.0,\"effect\":\"none\"},"
                    + "\"controller\"");

    private final StubWorld world = new StubWorld();
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    PlayerTest() throws IOException {
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private Player player(Map<String, Double> weights) {
        return Fixtures.player(world.url(), weights, clock, meters);
    }

    private JsonNode lastIntent() throws IOException {
        return JSON.readTree(world.posts().getLast().body());
    }

    private double count(Outcome outcome) {
        return meters.get("orbital.intents").tag("outcome", outcome.tag()).counter().count();
    }

    @Test
    void anIdleEntityGetsAStepOnTheOrbitWithIfVersionAndTheDraw() throws IOException {
        world.entity(StubWorld.ORBITER, 812, StubWorld.IDLE_ORBITER);
        Player player = player(Map.of("step", 2.0, "wait", 1.0, "throw", 1.0));
        Outcome outcome;
        do {
            outcome = player.turn();
        } while (!world.posts().isEmpty() && !lastIntent().path("action").path("type").asText().equals("step"));
        assertThat(outcome).isEqualTo(Outcome.OK);

        JsonNode intent = lastIntent();
        assertThat(intent.path("entity").asText()).isEqualTo(StubWorld.ORBITER);
        assertThat(intent.path("ifVersion").asLong()).isEqualTo(812);
        JsonNode to = intent.path("action").path("params").path("to");
        assertThat(Math.hypot(to.path("x").asDouble() - 5.0, to.path("y").asDouble() - 4.0)).isCloseTo(3.0,
                within(1e-9));
        assertThat(intent.path("action").path("params").size()).isEqualTo(1);

        JsonNode draw = intent.path("reported").path("draw");
        assertThat(draw.path("dist").asText()).isEqualTo("categorical");
        assertThat(JSON.convertValue(draw.path("weights"), double[].class)).containsExactly(2.0, 1.0, 1.0);
        assertThat(draw.path("u").asDouble()).isBetween(0.0, 1.0);
        assertThat(draw.path("value").asInt()).isZero();
        assertThat(fieldNames(draw)).containsExactlyInAnyOrder("dist", "weights", "u", "value");
        assertThat(JSON.convertValue(intent.path("reported").path("choices"), String[].class))
                .containsExactly("step", "wait", "throw");
        assertThat(player.lastAccepted()).contains(clock.instant());
        assertThat(count(Outcome.OK)).isPositive();
    }

    @Test
    void everyRequestCarriesTheKey() {
        player(Map.of("throw", 1.0)).turn();
        assertThat(world.requests).hasSize(3); // orbiter, pillar, intent
        assertThat(world.requests).allSatisfy(r -> assertThat(r.header("X-Api-Key")).isEqualTo(Fixtures.KEY));
    }

    @Test
    void waitSendsItsDuration() throws IOException {
        assertThat(player(Map.of("wait", 1.0)).turn()).isEqualTo(Outcome.OK);
        assertThat(lastIntent().path("action").toString())
                .isEqualTo("{\"type\":\"wait\",\"params\":{\"duration\":1.0}}");
    }

    @Test
    void throwAimsAtTheTargetsCurrentPosition() throws IOException {
        world.entity(StubWorld.PILLAR, 4, StubWorld.PILLAR_STATE.replace("\"x\":5.0,\"y\":4.0", "\"x\":3.5,\"y\":2.25"));
        assertThat(player(Map.of("throw", 1.0)).turn()).isEqualTo(Outcome.OK);
        JsonNode params = lastIntent().path("action").path("params");
        assertThat(lastIntent().path("action").path("type").asText()).isEqualTo("throw");
        assertThat(params.path("at").toString()).isEqualTo("{\"x\":3.5,\"y\":2.25}");
        assertThat(params.path("spread").asDouble()).isEqualTo(0.2);
        assertThat(params.path("recovery").asDouble()).isEqualTo(2.0);
        assertThat(params.path("projectile")).isEqualTo(JSON.readTree(Fixtures.PROJECTILE));
    }

    @Test
    void aBusyEntityIsLeftAlone() {
        world.entity(StubWorld.ORBITER, 3, BUSY_ORBITER);
        assertThat(player(Fixtures.WEIGHTS).turn()).isEqualTo(Outcome.BUSY);
        assertThat(world.posts()).isEmpty();
    }

    @Test
    void aStaleVersionIsReadAgainNextTurn() {
        world.intents(body -> Answer.error(409, "version mismatch"));
        Player player = player(Fixtures.WEIGHTS);
        assertThat(player.turn()).isEqualTo(Outcome.STALE);
        world.intents(body -> Answer.ok(StubWorld.ACCEPTED));
        world.entity(StubWorld.ORBITER, 2, StubWorld.IDLE_ORBITER);
        assertThat(player.turn()).isEqualTo(Outcome.OK);
        assertThat(world.requests.stream().filter(r -> r.path().endsWith(StubWorld.ORBITER)).count()).isEqualTo(2);
        assertThat(world.posts().getLast().body()).contains("\"ifVersion\":2");
        assertThat(count(Outcome.STALE)).isEqualTo(1.0);
    }

    @Test
    void anInvalidChoiceIsWarnedOnceAndSkippedForAMinute() {
        world.intents(body -> body.contains(WAIT) ? Answer.error(422, "wait: not in the entity's actions")
                : Answer.ok(StubWorld.ACCEPTED));
        Player player = player(Map.of("wait", 1.0, "step", 1.0));
        try (CapturedLogs logs = new CapturedLogs()) {
            int invalid = 0;
            for (int i = 0; i < 50 && invalid == 0; i++) {
                invalid += player.turn() == Outcome.INVALID ? 1 : 0;
            }
            assertThat(invalid).isEqualTo(1);
            int waitsBefore = waits();
            for (int i = 0; i < 50; i++) {
                assertThat(player.turn()).isEqualTo(Outcome.OK);
            }
            assertThat(waits()).isEqualTo(waitsBefore);

            clock.advance(Duration.ofMinutes(1));
            int more = 0;
            for (int i = 0; i < 50 && more == 0; i++) {
                more += player.turn() == Outcome.INVALID ? 1 : 0;
            }
            assertThat(more).isEqualTo(1);
            assertThat(logs.at(Level.WARN)).containsExactly(
                    "wait rejected, skipping it for a while: wait: not in the entity's actions");
        }
    }

    private int waits() {
        return (int) world.posts().stream().filter(r -> r.body().contains(WAIT)).count();
    }

    @Test
    void aGoneTargetMakesThrowUnavailable() {
        world.entityAnswer(StubWorld.PILLAR, Answer.error(404, "no resource"));
        Player player = player(Map.of("throw", 1.0));
        try (CapturedLogs logs = new CapturedLogs()) {
            assertThat(player.turn()).isEqualTo(Outcome.UNAVAILABLE);
            assertThat(player.turn()).isEqualTo(Outcome.UNAVAILABLE); // skipped: nothing open
            assertThat(world.posts()).isEmpty();
            assertThat(logs.at(Level.WARN)).containsExactly(
                    "throw rejected, skipping it for a while: target " + StubWorld.PILLAR + " not found");
        }
    }

    @Test
    void aRefusedKeyOrEntityIsAnErrorAndDenied() {
        Player player = player(Fixtures.WEIGHTS);
        try (CapturedLogs logs = new CapturedLogs()) {
            world.entityAnswer(StubWorld.ORBITER, Answer.error(401, null));
            assertThat(player.turn()).isEqualTo(Outcome.DENIED);
            world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER);
            world.intents(body -> Answer.error(403, "not controlled by orbital"));
            assertThat(player.turn()).isEqualTo(Outcome.DENIED);
            assertThat(logs.at(Level.ERROR)).hasSize(2).allSatisfy(m -> assertThat(m).startsWith("world refused orbital"));
        }
        assertThat(count(Outcome.DENIED)).isEqualTo(2.0);
    }

    @Test
    void anUnknownEntityIsNotFound() {
        world.entityAnswer(StubWorld.ORBITER, Answer.error(404, "no resource"));
        assertThat(player(Fixtures.WEIGHTS).turn()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void aServerErrorOrAnUnreadableEntityIsAnError() {
        Player player = player(Fixtures.WEIGHTS);
        world.intents(body -> Answer.error(500, "boom"));
        assertThat(player.turn()).isEqualTo(Outcome.ERROR);
        world.entity(StubWorld.ORBITER, 1, "{\"kind\":\"mobile\",\"position\":{\"x\":1.0,\"y\":1.0},\"wings\":2}");
        assertThat(player.turn()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void anUnreachableWorldIsReported() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        Player player = Fixtures.player("http://127.0.0.1:" + closed, Fixtures.WEIGHTS, clock, meters);
        assertThat(player.turn()).isEqualTo(Outcome.UNREACHABLE);
        assertThat(player.lastAccepted()).isEmpty();
    }

    @Test
    void theKeyNeverAppearsInTheLogs() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        try (CapturedLogs logs = new CapturedLogs()) {
            Player player = player(Fixtures.WEIGHTS);
            player.turn();
            world.entityAnswer(StubWorld.ORBITER, Answer.error(401, null));
            player.turn();
            world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER);
            for (int status : new int[] {403, 409, 422, 500}) {
                world.intents(body -> Answer.error(status, "rejected " + status));
                player.turn();
            }
            Fixtures.player("http://127.0.0.1:" + closed, Fixtures.WEIGHTS, clock, meters).turn();

            assertThat(logs.lines()).isNotEmpty();
            assertThat(logs.lines()).noneSatisfy(line -> assertThat(line).contains(Fixtures.KEY));
        }
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
