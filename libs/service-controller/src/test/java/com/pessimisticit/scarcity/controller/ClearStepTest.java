package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A clear step is one world lets run to its end; up to ten headings are tried, each one recorded. */
class ClearStepTest {

    private static final String MOUSE = "entity:00000000-0000-4000-8000-0000000000e1";

    /** A stone wall, the rectangle from (x0, y0) to (x1, y1). */
    private static Entity wall(double x0, double y0, double x1, double y1) {
        return Prototypes.get("stone-wall").withPolygon(List.of(new Position(x0, y0), new Position(x1, y0),
                new Position(x1, y1), new Position(x0, y1)));
    }

    /** The 10 x 8 room, enclosed by four walls 0.5 thick. */
    private static Map<String, Entity> room() {
        return Map.of(
                "entity:00000000-0000-4000-8000-0000000000c1", wall(-0.5, -0.5, 10.5, 0.0),
                "entity:00000000-0000-4000-8000-0000000000c2", wall(10.0, -0.5, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c3", wall(-0.5, 8.0, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c4", wall(-0.5, -0.5, 0.0, 8.5));
    }

    private static Entity mouse(double x, double y) {
        return Prototypes.get("mouse").withPosition(new Position(x, y)).withId(MOUSE);
    }

    private static Choice.Situation situation(Entity mouse, Map<String, Entity> others) {
        Map<String, Entity> all = new HashMap<>(others);
        all.put(MOUSE, mouse);
        AreaView view = AreaView.of(StubWorld.AREA, new Area("room", 0.0), all);
        return new Choice.Situation(new TurnFrame(StubWorld.AREA, MOUSE, 1, 0.0), mouse, view, null);
    }

    @Test
    void aStepIsClearOnlyIfItMeetsNothingBeforeItEnds() {
        Entity mouse = mouse(5.0, 4.0);
        AreaView view = situation(mouse, room()).areaView();
        assertThat(ClearStep.clear(view, mouse, new Position(6.0, 4.0))).isTrue();
        // Into the east wall: world would interrupt it at x 9.96.
        assertThat(ClearStep.clear(view, mouse, new Position(10.5, 4.0))).isFalse();
        // Ending just short of the wall's face, the mouse's radius away, is clear.
        assertThat(ClearStep.clear(view, mouse, new Position(9.96, 4.0))).isTrue();
        assertThat(ClearStep.clear(view, mouse, mouse.position())).as("zero length").isFalse();
    }

    @Test
    void aMouseTouchingAWallCanStepAwayButNotIntoIt() {
        Entity touching = mouse(0.04, 4.0);
        AreaView view = situation(touching, room()).areaView();
        assertThat(ClearStep.clear(view, touching, new Position(-1.0, 4.0))).isFalse();
        assertThat(ClearStep.clear(view, touching, new Position(1.04, 4.0))).isTrue();
    }

    @Test
    void aMouseInACornerMostlyFindsAClearHeadingAndRecordsEveryOneItTried() {
        // Touching the south and west walls: only headings into the room's quarter, about 1 in 4,
        // are clear, so ten tries miss about 0.75^10 = 6% of the time.
        Entity cornered = mouse(0.04, 0.04);
        Choice.Situation situation = situation(cornered, room());
        int feasible = 0;
        for (long seed = 0; seed < 200; seed++) {
            Plan plan = ClearStep.anyHeading(situation, new SplittableRandom(seed), 1.0);
            if (!plan.feasible()) {
                assertThat(plan.draws()).hasSize(ClearStep.TRIES);
                continue;
            }
            feasible++;
            List<String> names = List.copyOf(plan.draws().keySet());
            assertThat(names).hasSizeBetween(1, ClearStep.TRIES);
            for (int i = 0; i < names.size(); i++) {
                assertThat(names.get(i)).isEqualTo("heading." + (i + 1));
                Draw.Uniform heading = (Draw.Uniform) plan.draws().get(names.get(i));
                assertThat(heading.min()).isZero();
                assertThat(heading.max()).isEqualTo(2 * Math.PI);
            }
            Draw.Uniform taken = (Draw.Uniform) plan.draws().get(names.getLast());
            Position to = WorldJson.fromJson(plan.actionSpec().params(), Step.Params.class).to();
            assertThat(to).isEqualTo(ClearStep.along(cornered.position(), taken.value(), 1.0));
            assertThat(ClearStep.clear(situation.areaView(), cornered, to)).isTrue();
        }
        // Expected 188 of 200 (sd about 3.4).
        assertThat(feasible).isBetween(170, 200);
    }

    @Test
    void aMouseBoxedInHasNoClearStepAfterTenTries() {
        // A 0.5 m box round the mouse: no 1 m step fits.
        Map<String, Entity> box = Map.of(
                "entity:00000000-0000-4000-8000-0000000000d1", wall(4.5, 3.5, 5.5, 3.75),
                "entity:00000000-0000-4000-8000-0000000000d2", wall(5.25, 3.5, 5.5, 4.5),
                "entity:00000000-0000-4000-8000-0000000000d3", wall(4.5, 4.25, 5.5, 4.5),
                "entity:00000000-0000-4000-8000-0000000000d4", wall(4.5, 3.5, 4.75, 4.5));
        Plan plan = ClearStep.anyHeading(situation(mouse(5.0, 4.0), box), new SplittableRandom(1), 1.0);
        assertThat(plan.feasible()).isFalse();
        assertThat(plan.reason()).isEqualTo("no clear step in 10 headings");
        assertThat(plan.draws()).hasSize(ClearStep.TRIES);
    }

    @Test
    void anEntityThatCannotMoveHasNoClearStep() {
        Entity still = mouse(5.0, 4.0).withSpeed(null);
        assertThat(ClearStep.clear(situation(still, room()).areaView(), still, new Position(6.0, 4.0))).isFalse();
    }
}
