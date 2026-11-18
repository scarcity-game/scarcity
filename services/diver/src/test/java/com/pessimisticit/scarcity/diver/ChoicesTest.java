package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Step;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Against a stub world's area: exactly one choice is open on every turn. Step toward the stairs
 * down (never the stairs up) while out of reach, traverse them within reach, wait if there are none.
 */
class ChoicesTest {

    private static final DiverProperties SETTINGS = new DiverProperties(
            new DiverProperties.StepSettings(1.0), new DiverProperties.WaitSettings(1.0));

    private final StubWorld stubWorld;
    private final World world;
    private final List<Choice> choices;
    private long version;

    ChoicesTest() throws Exception {
        this.stubWorld = new StubWorld();
        this.world = Fixtures.world(stubWorld);
        Stairway stairway = new Stairway();
        this.choices = List.of(new ApproachChoice(stairway, SETTINGS), new DescendChoice(stairway),
                new WaitChoice(stairway, SETTINGS));
    }

    @AfterEach
    void stop() {
        stubWorld.close();
    }

    /** A level with the diver at {@code (x, y)}, stairs up at (5, 4) and, if {@code down}, stairs down at (1.5, 1.5). */
    private Entity level(double x, double y, boolean down) {
        version++;
        stubWorld.entity(Fixtures.DIVER, version, Fixtures.diver(x, y));
        stubWorld.entity(Fixtures.STAIRS_UP, 1, Fixtures.stairs("stairs-up", 5.0, 4.0));
        stubWorld.entity(Fixtures.STAIRS_DOWN, 1, Fixtures.stairs("stairs-down", 1.5, 1.5));
        stubWorld.children(StubWorld.AREA, down
                ? List.of(Fixtures.STAIRS_UP, Fixtures.STAIRS_DOWN, Fixtures.DIVER)
                : List.of(Fixtures.STAIRS_UP, Fixtures.DIVER));
        return world.entity(Fixtures.DIVER);
    }

    private TurnFrame turn() {
        return new TurnFrame(StubWorld.AREA, Fixtures.DIVER, version, 0.0);
    }

    private List<String> open(Entity self) {
        return choices.stream().filter(c -> c.open(turn(), self, world)).map(Choice::name).toList();
    }

    @Test
    void outOfReachItStepsAtMostTheMaxDistanceTowardTheStairsDown() {
        Entity self = level(4.0, 4.0, true);
        assertThat(open(self)).containsExactly("step");

        Choice.Plan plan = choices.getFirst().plan(turn(), self, world);
        Position to = WorldJson.fromJson(plan.actionSpec().params(), Step.Params.class).to();
        assertThat(self.position().distanceTo(to)).isCloseTo(1.0, offset(1e-9));
        Position down = new Position(1.5, 1.5);
        assertThat(to.distanceTo(down)).as("straight at the stairs down")
                .isCloseTo(self.position().distanceTo(down) - 1.0, offset(1e-9));
    }

    @Test
    void theLastStepStopsWithinReach() {
        Entity self = level(2.7, 1.5, true);
        assertThat(open(self)).containsExactly("step");
        Position to = WorldJson.fromJson(choices.getFirst().plan(turn(), self, world).actionSpec().params(),
                Step.Params.class).to();
        assertThat(to.distanceTo(new Position(1.5, 1.5))).isCloseTo(ApproachChoice.STOP_SHORT, offset(1e-9));
        assertThat(ApproachChoice.STOP_SHORT).isLessThan(Traverse.REACH);
    }

    @Test
    void withinReachItTakesTheStairsDown() {
        Entity self = level(2.0, 1.5, true);
        assertThat(open(self)).containsExactly("traverse");
        Choice.Plan plan = choices.get(1).plan(turn(), self, world);
        assertThat(WorldJson.fromJson(plan.actionSpec().params(), Traverse.Params.class).stairs())
                .isEqualTo(Fixtures.STAIRS_DOWN);
    }

    @Test
    void besideTheStairsUpItStillGoesForTheStairsDown() {
        Entity self = level(5.0, 4.5, true);
        assertThat(open(self)).containsExactly("step");
    }

    @Test
    void aLevelWithNoStairsDownMakesItWait() {
        Entity self = level(4.0, 4.0, false);
        assertThat(open(self)).containsExactly("wait");
    }

    @Test
    void theAreaIsReadOncePerTurn() {
        Entity self = level(4.0, 4.0, true);
        open(self);
        choices.getFirst().plan(turn(), self, world);
        assertThat(stubWorld.worldRequests()).filteredOn(r -> r.path().endsWith("/children")).hasSize(1);
    }
}
