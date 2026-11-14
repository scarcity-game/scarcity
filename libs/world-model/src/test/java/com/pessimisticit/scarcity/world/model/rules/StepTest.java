package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code step} (docs/actions.md, "Action types"), through the catalogue as an intent would start it. */
class StepTest {

    private static final Catalogue CATALOGUE = Catalogue.standard();

    private static ActionSpec step(double x, double y) {
        return new ActionSpec("step", TestArea.object("{\"to\":{\"x\":" + x + ",\"y\":" + y + "}}"));
    }

    private static Applied run(EntityState mover, double x, double y) {
        Actor self = TestArea.actor(ORBITER, mover, Map.of(PILLAR, TestArea.pillar()));
        BoundAction<?> step = CATALOGUE.start(step(x, y), self);
        return step.apply(self);
    }

    private static Step.Result result(Applied a) {
        return (Step.Result) a.result();
    }

    private static EntityState withRadius(double r) {
        return TestArea.state("""
                {"kind":"mobile","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,"radius":%s,
                 "actions":["step"]}""".formatted(r));
    }

    @Test
    void aFreeStepReachesItsPointAndTakesDistanceOverSpeed() {
        Actor self = TestArea.actor(ORBITER, TestArea.orbiter(), Map.of(PILLAR, TestArea.pillar()));
        BoundAction<?> step = CATALOGUE.start(step(8.0, 6.0), self);
        assertThat(step.duration(self.state())).isEqualTo(4.0);
        Applied a = step.apply(self);
        assertThat(result(a).reached()).isTrue();
        assertThat(result(a).stoppedAt()).isEqualTo(new Position(8.0, 6.0));
        assertThat(a.state().position()).isEqualTo(new Position(8.0, 6.0));
    }

    @Test
    void thePillarStopsAStepAtItsEdge() {
        Applied a = run(TestArea.orbiter(), 2.0, 4.0);
        assertThat(result(a).reached()).isFalse();
        assertThat(result(a).stoppedAt().x()).isCloseTo(5.5, within(1e-12));
        assertThat(result(a).stoppedAt().y()).isCloseTo(4.0, within(1e-12));
        assertThat(a.state().position()).isEqualTo(result(a).stoppedAt());
    }

    @Test
    void aMoverKeepsItsOwnRadiusClearOfThePillar() {
        Applied a = run(withRadius(0.3), 2.0, 4.0);
        assertThat(result(a).stoppedAt().x()).isCloseTo(5.8, within(1e-12));
        assertThat(result(a).reached()).isFalse();
    }

    @Test
    void aWallStopsAMoverWithARadius() {
        Applied a = run(withRadius(0.5), 9.8, 4.0);
        assertThat(result(a).reached()).isFalse();
        assertThat(result(a).stoppedAt().x()).isCloseTo(9.5, within(1e-12));
    }

    @Test
    void aMoverOnThePillarsEdgeCannotGoInButCanLeave() {
        EntityState touching = TestArea.orbiter().withPosition(new Position(5.5, 4.0));
        Applied in = run(touching, 2.0, 4.0);
        assertThat(result(in).stoppedAt()).isEqualTo(new Position(5.5, 4.0));
        assertThat(result(in).reached()).isFalse();
        Applied out = run(touching, 7.0, 4.0);
        assertThat(result(out).reached()).isTrue();
    }

    @Test
    void aMoverIgnoresItself() {
        Applied a = run(withRadius(0.5), 8.0, 6.0);
        assertThat(result(a).reached()).isTrue();
    }

    @Test
    void theResultIsInTheContractShape() {
        Applied a = run(TestArea.orbiter(), 2.0, 4.0);
        assertThat(WorldJson.toTree(a.result())).isEqualTo(TestArea.tree("""
                {"to":{"x":2.0,"y":4.0},"reached":false,"stoppedAt":{"x":5.5,"y":4.0}}"""));
    }

    @Test
    void aZeroLengthStepIsInvalid() {
        assertThatThrownBy(() -> run(TestArea.orbiter(), 8.0, 4.0))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step: to: a zero-length step (it is the entity's position)");
    }

    @Test
    void aStepOutsideTheAreaIsInvalid() {
        assertThatThrownBy(() -> run(TestArea.orbiter(), 10.0, 4.0))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("step: to: ")
                .hasMessageContaining("outside the area (10.0 x 8.0)");
        assertThatThrownBy(() -> run(TestArea.orbiter(), 3.0, -0.1))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("step: to: ");
    }

    @Test
    void anEntityWithoutSpeedCannotStep() {
        EntityState still = TestArea.state("""
                {"kind":"mobile","name":"stone","position":{"x":8.0,"y":4.0},"actions":["step"]}""");
        assertThatThrownBy(() -> run(still, 8.0, 6.0))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step: speed: the entity has none, so it can't step");
    }
}
