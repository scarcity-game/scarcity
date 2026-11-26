package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.controller.Choice.Situation;
import com.pessimisticit.scarcity.controller.ClearStep;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A mouse's step: a drawn heading and a fixed distance, clear of every wall and mouse this turn (#316). */
class ScurryChoiceTest {

    private static final String MOUSE = "entity:00000000-0000-4000-8000-0000000000e1";

    private static Entity wall(double x0, double y0, double x1, double y1) {
        return Materials.get("granite").wall(List.of(new Position(x0, y0), new Position(x1, y0),
                new Position(x1, y1), new Position(x0, y1)));
    }

    /** The mouse at (x, y) in the 10 x 8 room inside four walls. */
    private static Situation inRoom(double x, double y) {
        Entity mouse = WorldJson.fromJson(Fixtures.mouse(x, y), Entity.class).withId(MOUSE);
        Map<String, Entity> all = new HashMap<>(Map.of(
                "entity:00000000-0000-4000-8000-0000000000c1", wall(-0.5, -0.5, 10.5, 0.0),
                "entity:00000000-0000-4000-8000-0000000000c2", wall(10.0, -0.5, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c3", wall(-0.5, 8.0, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c4", wall(-0.5, -0.5, 0.0, 8.5)));
        all.put(MOUSE, mouse);
        AreaView view = AreaView.of("area:00000000-0000-4000-8000-000000000002", new Area("room", 0.0), all);
        return new Situation(new TurnFrame(view.areaId(), MOUSE, 1, 0.0), mouse, view);
    }

    @Test
    void inOpenFloorTheFirstHeadingIsTakenAndReported() {
        Situation situation = inRoom(5.0, 4.0);
        Plan plan = new ScurryChoice(1.0, new SplittableRandom(3)).plan(situation);

        assertThat(plan.draws()).containsOnlyKeys("heading.1");
        Draw.Uniform heading = (Draw.Uniform) plan.draws().get("heading.1");
        assertThat(heading.min()).isEqualTo(0.0);
        assertThat(heading.max()).isEqualTo(2 * Math.PI);
        Position to = WorldJson.fromJson(plan.actionSpec().params(), Step.Params.class).to();
        assertThat(plan.actionSpec().type()).isEqualTo("step");
        assertThat(to.x()).isCloseTo(5.0 + Math.cos(heading.value()), offset(1e-9));
        assertThat(to.y()).isCloseTo(4.0 + Math.sin(heading.value()), offset(1e-9));
    }

    @Test
    void inAWalledRoomAlmostEveryTurnHasAClearStepAndEachOneIsClear() {
        ScurryChoice choice = new ScurryChoice(1.0, new SplittableRandom(1));
        SplittableRandom where = new SplittableRandom(2);
        int feasible = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            // Anywhere the mouse (radius 0.2) fits, against the walls included.
            Situation situation = inRoom(where.nextDouble(0.2, 9.8), where.nextDouble(0.2, 7.8));
            Plan plan = choice.plan(situation);
            if (plan.feasible()) {
                feasible++;
                Position to = WorldJson.fromJson(plan.actionSpec().params(), Step.Params.class).to();
                assertThat(ClearStep.clear(situation.areaView(), situation.entity(), to)).isTrue();
            } else {
                assertThat(plan.draws()).hasSize(ClearStep.TRIES);
            }
        }
        assertThat(feasible / (double) n).as("turns with a clear step").isGreaterThan(0.99);
    }
}
