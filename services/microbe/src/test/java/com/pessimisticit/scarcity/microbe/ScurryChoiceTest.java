package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.controller.ServiceToken;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.TestClock;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** A mouse's step: a uniform heading, a fixed distance, always inside the area. */
class ScurryChoiceTest {

    private static final Area ROOM = new Area("room", 10.0, 8.0, 0.0).withId(StubWorld.AREA).withVersion(1);

    private final StubWorld stubWorld;

    ScurryChoiceTest() throws Exception {
        this.stubWorld = new StubWorld();
    }

    @AfterEach
    void stop() {
        stubWorld.close();
    }

    private static Entity mouseAt(double x, double y) {
        return WorldJson.fromJson(Fixtures.mouse(x, y), Entity.class);
    }

    @Test
    void itStepsTheDistanceAlongTheDrawnHeadingAndReportsTheDraw() {
        stubWorld.area(ROOM);
        World world = new World(RestClient.builder(), Fixtures.props(stubWorld.url(), "ws://unused"),
                new ServiceToken(RestClient.builder(), Fixtures.props(stubWorld.url(), "ws://unused"), new TestClock()));
        ScurryChoice choice = new ScurryChoice(1.0, new SplittableRandom(3));

        Plan plan = choice.plan(new TurnFrame(StubWorld.AREA, "entity:m", 1, 0.0), mouseAt(5.0, 4.0), world);

        Draw.Uniform heading = (Draw.Uniform) plan.draws().get("heading");
        assertThat(heading.min()).isEqualTo(0.0);
        assertThat(heading.max()).isEqualTo(2 * Math.PI);
        Position to = WorldJson.fromJson(plan.actionSpec().params(), Step.Params.class).to();
        assertThat(plan.actionSpec().type()).isEqualTo("step");
        assertThat(to.x()).isCloseTo(5.0 + Math.cos(heading.value()), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(to.y()).isCloseTo(4.0 + Math.sin(heading.value()), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void itStaysInsideTheAreaAndNeverStepsInPlace() {
        ScurryChoice choice = new ScurryChoice(1.0, new SplittableRandom(1));
        SplittableRandom where = new SplittableRandom(2);
        for (int i = 0; i < 10_000; i++) {
            Entity mouse = mouseAt(where.nextDouble(0.2, 9.8), where.nextDouble(0.2, 7.8));
            double angle = where.nextDouble(0.0, 2 * Math.PI);
            Position to = choice.toward(mouse, ROOM, angle);
            assertThat(to.x()).isBetween(0.2, 9.8);
            assertThat(to.y()).isBetween(0.2, 7.8);
            assertThat(ROOM.contains(to)).isTrue();
        }
        // In a corner, heading into it: the point clamps onto the mouse, so the plan goes the other way.
        Entity cornered = mouseAt(0.2, 0.2);
        assertThat(choice.toward(cornered, ROOM, 5 * Math.PI / 4)).isEqualTo(cornered.position());
    }
}
