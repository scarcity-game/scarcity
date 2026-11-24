package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.StubWorld.Answer;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every choice is planned before the draw, the draw is only among feasible ones, and a refusal is
 * a wait for that entity alone: nothing is benched (#316).
 */
class PlayerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "entity:00000000-0000-4000-8000-0000000000a1";
    private static final String B = "entity:00000000-0000-4000-8000-0000000000b1";

    private final StubWorld stubWorld;
    private final List<String> inArea;

    PlayerTest() throws IOException {
        this.stubWorld = new StubWorld();
        this.inArea = new ArrayList<>();
    }

    @AfterEach
    void stop() {
        stubWorld.close();
    }

    /** A scurry like microbe's (a clear 1 m step on a drawn heading) and a wait, weighted 3:1. */
    private Player player(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        Choice scurry = new Choice() {
            @Override
            public String name() {
                return "step";
            }

            @Override
            public Plan plan(Situation situation) {
                return ClearStep.anyHeading(situation, rng, 1.0);
            }
        };
        Choice wait = new Choice() {
            @Override
            public String name() {
                return "wait";
            }

            @Override
            public Plan plan(Situation situation) {
                return Plan.of(Wait.INSTANCE.spec(new Wait.Params(1.0)));
            }
        };
        ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
        TestClock clock = new TestClock();
        World world = new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, clock));
        Chooser chooser = new Chooser(List.of(scurry, wait), Map.of("step", 3.0, "wait", 1.0), rng);
        return new Player(world, chooser, props, clock, new SimpleMeterRegistry());
    }

    /** Puts {@code entity} in the stub's area as {@code id} (its JSON without the keys the stub adds). */
    private void put(String id, Entity entity) {
        String json = WorldJson.toJson(entity).replace("{\"resourceKind\":\"entity\",", "{");
        stubWorld.entity(id, 1, json);
        inArea.add(id);
        stubWorld.children(StubWorld.AREA, inArea);
    }

    private void mouse(String id, double x, double y) {
        put(id, Prototypes.get("mouse").withPosition(new Position(x, y)).withActions(List.of("step", "wait"))
                .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("tester")));
    }

    private void wall(String id, double x0, double y0, double x1, double y1) {
        put(id, Prototypes.get("stone-wall").withPolygon(List.of(new Position(x0, y0), new Position(x1, y0),
                new Position(x1, y1), new Position(x0, y1))));
    }

    private JsonNode lastIntent() {
        return JSON.readTree(stubWorld.posts().getLast().body());
    }

    @Test
    void aRefusalIsAWaitForThatEntityAloneAndBenchesNothing() {
        mouse(A, 2.0, 2.0);
        mouse(B, 7.0, 6.0);
        // world refuses every step of A's, and accepts everything else.
        stubWorld.intents(body -> body.contains(A) && body.contains("\"type\":\"step\"")
                ? Answer.error(422, "step: to: the step starts touching a wall, which it moves toward")
                : Answer.ok(StubWorld.ACCEPTED));
        Player player = player(3);

        Outcome refused = null;
        for (int i = 0; i < 50 && refused != Outcome.FALLBACK; i++) {
            refused = player.turn(stubWorld.turn(A));
        }
        assertThat(refused).as("A's step refused within 50 turns").isEqualTo(Outcome.FALLBACK);
        JsonNode fallback = lastIntent();
        assertThat(fallback.path("entity").asString()).isEqualTo(A);
        assertThat(fallback.path("actionSpec").path("type").asString()).isEqualTo("wait");
        assertThat(fallback.path("reported").path("fallback").path("reason").asString()).startsWith("rejected: ");

        // B's very next turn, and A's, still have step among the choices drawn from.
        assertThat(player.turn(stubWorld.turn(B))).isEqualTo(Outcome.OK);
        assertThat(lastIntent().path("reported").path("choices").toString()).isEqualTo("[\"step\",\"wait\"]");
        player.turn(stubWorld.turn(A));
        JsonNode again = lastIntent();
        assertThat(again.path("entity").asString()).isEqualTo(A);
        assertThat(again.path("reported").path("choices").toString().contains("step")
                || again.path("reported").path("fallback").path("reason").asString().startsWith("rejected: "))
                .as("A drew among step and wait again").isTrue();
    }

    @Test
    void aMouseWithNoClearStepIsNotOfferedStepAndWaitsWithWhatItTried() {
        // A 0.5 m box round A: no 1 m step fits, so only wait is drawn.
        mouse(A, 5.0, 4.0);
        wall("entity:00000000-0000-4000-8000-0000000000d1", 4.5, 3.5, 5.5, 3.75);
        wall("entity:00000000-0000-4000-8000-0000000000d2", 5.25, 3.5, 5.5, 4.5);
        wall("entity:00000000-0000-4000-8000-0000000000d3", 4.5, 4.25, 5.5, 4.5);
        wall("entity:00000000-0000-4000-8000-0000000000d4", 4.5, 3.5, 4.75, 4.5);
        stubWorld.intents(body -> Answer.ok(StubWorld.ACCEPTED));
        Player player = player(5);

        for (int i = 0; i < 20; i++) {
            assertThat(player.turn(stubWorld.turn(A))).isEqualTo(Outcome.OK);
            JsonNode reported = lastIntent().path("reported");
            assertThat(lastIntent().path("actionSpec").path("type").asString()).isEqualTo("wait");
            assertThat(reported.path("choices").toString()).isEqualTo("[\"wait\"]");
            JsonNode step = reported.path("considered").path("step");
            assertThat(step.path("infeasible").asString()).isEqualTo("no clear step in 10 headings");
            assertThat(step.path("plan").size()).isEqualTo(ClearStep.TRIES);
            assertThat(step.path("plan").path("heading.1").path("dist").asString()).isEqualTo("uniform");
        }
    }

    @Test
    void theDrawnStepIsClearAndAnUnchosenStepsDrawsAreReportedToo() {
        mouse(A, 5.0, 4.0);
        stubWorld.intents(body -> Answer.ok(StubWorld.ACCEPTED));
        Player player = player(11);
        boolean sawStep = false;
        boolean sawWait = false;
        for (int i = 0; i < 100 && !(sawStep && sawWait); i++) {
            player.turn(stubWorld.turn(A));
            JsonNode intent = lastIntent();
            JsonNode reported = intent.path("reported");
            assertThat(reported.path("choices").toString()).isEqualTo("[\"step\",\"wait\"]");
            if (intent.path("actionSpec").path("type").asString().equals("step")) {
                sawStep = true;
                assertThat(reported.path("plan").path("heading.1").path("dist").asString()).isEqualTo("uniform");
                assertThat(reported.has("considered")).as("wait drew nothing").isFalse();
            } else {
                sawWait = true;
                assertThat(reported.path("considered").path("step").path("plan").path("heading.1").path("dist")
                        .asString()).isEqualTo("uniform");
                assertThat(reported.path("considered").path("step").has("infeasible")).isFalse();
            }
        }
        assertThat(sawStep && sawWait).isTrue();
    }
}
