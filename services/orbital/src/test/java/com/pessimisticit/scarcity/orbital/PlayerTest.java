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

    private Outcome turn(Player player) {
        return player.turn(world.turn(StubWorld.ORBITER));
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
            outcome = turn(player);
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
    void everyWorldRequestCarriesTheServiceTokenAndNotTheKey() {
        Player player = player(Map.of("throw", 1.0));
        turn(player);
        turn(player);
        assertThat(world.tokensIssued()).isEqualTo(1);
        assertThat(world.worldRequests()).hasSize(6); // orbiter, pillar, intent; twice
        assertThat(world.worldRequests()).allSatisfy(r -> {
            assertThat(r.header("Authorization")).isEqualTo("Bearer token-1");
            assertThat(r.header("X-Api-Key")).isNull();
        });
    }

    @Test
    void restCallsSwitchToANewTokenTenMinutesIntoAFifteenMinuteOne() {
        Player player = player(Map.of("wait", 1.0)); // a turn: GET the entity, POST the intent
        turn(player);
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        turn(player);
        clock.advance(Duration.ofSeconds(1));
        turn(player);
        clock.advance(Duration.ofMinutes(9));
        turn(player);
        assertThat(world.tokensIssued()).isEqualTo(2);
        assertThat(world.worldRequests()).extracting(r -> r.header("Authorization")).containsExactly(
                "Bearer token-1", "Bearer token-1", "Bearer token-1", "Bearer token-1",
                "Bearer token-2", "Bearer token-2", "Bearer token-2", "Bearer token-2");
    }

    @Test
    void onlyTheEntitysActionsAreChosen() throws IOException {
        world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER.replace("[\"step\",\"wait\",\"throw\"]", "[\"wait\"]"));
        Player player = player(Fixtures.WEIGHTS);
        for (int i = 0; i < 20; i++) {
            assertThat(turn(player)).isEqualTo(Outcome.OK);
            assertThat(lastIntent().path("action").path("type").asText()).isEqualTo("wait");
            assertThat(JSON.convertValue(lastIntent().path("reported").path("choices"), String[].class))
                    .containsExactly("wait");
        }
    }

    @Test
    void anEntityWithNoneOfOrbitalsChoicesAmongItsActionsIsUnavailable() {
        world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER.replace("[\"step\",\"wait\",\"throw\"]", "[\"fly\"]"));
        assertThat(turn(player(Fixtures.WEIGHTS))).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(world.posts()).isEmpty();
    }

    @Test
    void waitSendsItsDuration() throws IOException {
        assertThat(turn(player(Map.of("wait", 1.0)))).isEqualTo(Outcome.OK);
        assertThat(lastIntent().path("action").toString())
                .isEqualTo("{\"type\":\"wait\",\"params\":{\"duration\":1.0}}");
    }

    @Test
    void throwAimsAtTheTargetsCurrentPosition() throws IOException {
        world.entity(StubWorld.PILLAR, 4, StubWorld.PILLAR_STATE.replace("\"x\":5.0,\"y\":4.0", "\"x\":3.5,\"y\":2.25"));
        assertThat(turn(player(Map.of("throw", 1.0)))).isEqualTo(Outcome.OK);
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
        assertThat(turn(player(Fixtures.WEIGHTS))).isEqualTo(Outcome.STALE);
        assertThat(world.posts()).isEmpty();
    }

    @Test
    void aTurnForAnOlderVersionIsLeftForTheNextTurn() {
        world.entity(StubWorld.ORBITER, 5, StubWorld.IDLE_ORBITER);
        Player player = player(Fixtures.WEIGHTS);
        assertThat(player.turn(new TurnFrame(StubWorld.AREA, StubWorld.ORBITER, 4, 0.0))).isEqualTo(Outcome.STALE);
        assertThat(world.posts()).isEmpty();
        assertThat(count(Outcome.STALE)).isEqualTo(1.0);
    }

    @Test
    void a409IsAConflictAndTheNextTurnPostsOnItsVersion() {
        world.intents(body -> Answer.error(409, "not up"));
        Player player = player(Fixtures.WEIGHTS);
        assertThat(turn(player)).isEqualTo(Outcome.CONFLICT);
        world.intents(body -> Answer.ok(StubWorld.ACCEPTED));
        world.entity(StubWorld.ORBITER, 2, StubWorld.IDLE_ORBITER);
        assertThat(turn(player)).isEqualTo(Outcome.OK);
        assertThat(world.posts().getLast().body()).contains("\"ifVersion\":2");
        assertThat(count(Outcome.CONFLICT)).isEqualTo(1.0);
    }

    @Test
    void anInvalidChoiceIsWarnedOnceAndSkippedForAMinute() {
        world.intents(body -> body.contains(WAIT) ? Answer.error(422, "wait: not in the entity's actions")
                : Answer.ok(StubWorld.ACCEPTED));
        Player player = player(Map.of("wait", 1.0, "step", 1.0));
        try (CapturedLogs logs = new CapturedLogs()) {
            int invalid = 0;
            for (int i = 0; i < 50 && invalid == 0; i++) {
                invalid += turn(player) == Outcome.INVALID ? 1 : 0;
            }
            assertThat(invalid).isEqualTo(1);
            int waitsBefore = waits();
            for (int i = 0; i < 50; i++) {
                assertThat(turn(player)).isEqualTo(Outcome.OK);
            }
            assertThat(waits()).isEqualTo(waitsBefore);

            clock.advance(Duration.ofMinutes(1));
            int more = 0;
            for (int i = 0; i < 50 && more == 0; i++) {
                more += turn(player) == Outcome.INVALID ? 1 : 0;
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
            assertThat(turn(player)).isEqualTo(Outcome.UNAVAILABLE);
            assertThat(turn(player)).isEqualTo(Outcome.UNAVAILABLE); // skipped: nothing open
            assertThat(world.posts()).isEmpty();
            assertThat(logs.at(Level.WARN)).containsExactly(
                    "throw rejected, skipping it for a while: target " + StubWorld.PILLAR + " not found",
                    StubWorld.ORBITER + " has no choice it may make now among its actions [step, wait, throw]");
        }
    }

    @Test
    void aRefusedTokenOrEntityIsDeniedAndA401FetchesANewToken() {
        Player player = player(Fixtures.WEIGHTS);
        try (CapturedLogs logs = new CapturedLogs()) {
            world.entityAnswer(StubWorld.ORBITER, Answer.error(401, null));
            assertThat(player.turn(new TurnFrame(StubWorld.AREA, StubWorld.ORBITER, 1, 0.0))).isEqualTo(Outcome.DENIED);
            world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER);
            world.intents(body -> Answer.error(403, "not controlled by orbital"));
            assertThat(turn(player)).isEqualTo(Outcome.DENIED);
            assertThat(logs.at(Level.ERROR)).hasSize(2).allSatisfy(m -> assertThat(m).startsWith("world refused orbital"));
        }
        assertThat(world.tokensIssued()).isEqualTo(2);
        assertThat(world.worldRequests().getLast().header("Authorization")).isEqualTo("Bearer token-2");
        assertThat(count(Outcome.DENIED)).isEqualTo(2.0);
    }

    @Test
    void aRefusedKeyIsDeniedAndNothingReachesWorld() {
        Player player = new Player(Fixtures.world(world.url(), Fixtures.token(world.url(), "wrong", clock)),
                Fixtures.chooser(Fixtures.WEIGHTS, 1, clock), clock, meters);
        try (CapturedLogs logs = new CapturedLogs()) {
            assertThat(turn(player)).isEqualTo(Outcome.DENIED);
            assertThat(logs.at(Level.ERROR)).containsExactly(
                    "auth refused the service token: 401: check ORBITAL_API_KEY");
        }
        assertThat(world.worldRequests()).isEmpty();
    }

    @Test
    void anUnknownEntityIsNotFound() {
        world.entityAnswer(StubWorld.ORBITER, Answer.error(404, "no resource"));
        assertThat(turn(player(Fixtures.WEIGHTS))).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void aServerErrorOrAnUnreadableEntityIsAnError() {
        Player player = player(Fixtures.WEIGHTS);
        world.intents(body -> Answer.error(500, "boom"));
        assertThat(turn(player)).isEqualTo(Outcome.ERROR);
        world.entity(StubWorld.ORBITER, 1, "{\"kind\":\"mobile\",\"position\":{\"x\":1.0,\"y\":1.0},\"wings\":2}");
        assertThat(turn(player)).isEqualTo(Outcome.ERROR);
    }

    @Test
    void anUnreachableWorldIsReported() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        Player player = Fixtures.player("http://127.0.0.1:" + closed, Fixtures.WEIGHTS, clock, meters);
        assertThat(turn(player)).isEqualTo(Outcome.UNREACHABLE);
        assertThat(player.lastAccepted()).isEmpty();
    }

    @Test
    void neitherTheKeyNorTheTokenAppearsInTheLogs() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        try (CapturedLogs logs = new CapturedLogs()) {
            Player player = player(Fixtures.WEIGHTS);
            turn(player);
            world.entityAnswer(StubWorld.ORBITER, Answer.error(401, null));
            turn(player);
            world.entity(StubWorld.ORBITER, 1, StubWorld.IDLE_ORBITER);
            for (int status : new int[] {403, 409, 422, 500}) {
                world.intents(body -> Answer.error(status, "rejected " + status));
                turn(player);
            }
            turn(Fixtures.player("http://127.0.0.1:" + closed, Fixtures.WEIGHTS, clock, meters));

            assertThat(logs.lines()).isNotEmpty();
            assertThat(logs.lines()).noneSatisfy(line -> assertThat(line).contains(Fixtures.KEY));
            assertThat(logs.lines()).noneSatisfy(line -> assertThat(line).contains("token-"));
        }
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
