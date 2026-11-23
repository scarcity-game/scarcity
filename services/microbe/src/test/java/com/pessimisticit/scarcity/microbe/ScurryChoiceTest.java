package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.controller.ServiceToken;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.TestClock;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** A mouse's step: a uniform heading and a fixed distance; a wall in the way interrupts it in world. */
class ScurryChoiceTest {

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
    void itStepsTheFullDistanceEvenTowardAWall() {
        ScurryChoice choice = new ScurryChoice(1.0, new SplittableRandom(1));
        // Next to the west wall, heading into it: the point is past it, and world stops the mouse there.
        Position to = choice.toward(mouseAt(0.2, 4.0), Math.PI);
        assertThat(to.x()).isCloseTo(-0.8, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(to.y()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
