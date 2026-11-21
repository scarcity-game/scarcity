package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code step} (docs/action-types.md, "Action types"), through the catalogue as an intent would start it. */
class StepTest {

    private static final Catalogue CATALOGUE = Catalogue.standard();

    private static ActionSpec step(double x, double y) {
        return new ActionSpec("step", TestArea.object("{\"to\":{\"x\":" + x + ",\"y\":" + y + "}}"));
    }

    private static Applied run(Entity mover, double x, double y) {
        Actor self = TestArea.actor(ORBITER, mover, Map.of(PILLAR, TestArea.pillar()));
        BoundAction<?> step = CATALOGUE.start(step(x, y), self);
        return step.apply(self);
    }

    private static Step.Result result(Applied a) {
        return (Step.Result) a.result();
    }

    private static Entity withRadius(double r) {
        return TestArea.state("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"orbiter","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,"radius":%s,
                 "actions":["step"]}""".formatted(r));
    }

    @Test
    void aFreeStepReachesItsPointAndTakesDistanceOverSpeed() {
        Actor self = TestArea.actor(ORBITER, TestArea.orbiter(), Map.of(PILLAR, TestArea.pillar()));
        BoundAction<?> step = CATALOGUE.start(step(8.0, 6.0), self);
        assertThat(step.duration(self.entity())).isEqualTo(4.0);
        Applied a = step.apply(self);
        assertThat(result(a).reached()).isTrue();
        assertThat(result(a).stoppedAt()).isEqualTo(new Position(8.0, 6.0));
        assertThat(a.entity().position()).isEqualTo(new Position(8.0, 6.0));
    }

    /** The first contact of a step by {@code mover} to (x, y) with the pillar or the boundary. */
    private static Contacts.Contact contact(Entity mover, double x, double y) {
        Actor self = TestArea.actor(ORBITER, mover, Map.of(PILLAR, TestArea.pillar()));
        Motion motion = CATALOGUE.start(step(x, y), self).motion(self).orElseThrow();
        return Contacts.first(self.areaView(), Map.of(), ORBITER, motion);
    }

    @Test
    void thePillarIsTouchedAtItsEdge() {
        Contacts.Contact c = contact(TestArea.orbiter(), 2.0, 4.0);
        assertThat(c.other()).isEqualTo(PILLAR);
        assertThat(c.time()).as("from x 8.0 to the edge at 5.5, at speed 0.5").isCloseTo(5.0, within(1e-12));
    }

    @Test
    void anEntityThatOverlapsIsNoObstacle() {
        Entity stairs = Stairs.down("stairs down")
                .withPosition(new Position(7.0, 4.0));
        Actor self = TestArea.actor(ORBITER, withRadius(0.3), Map.of("entity:00000000-0000-4000-8000-0000000000aa", stairs));
        Applied a = CATALOGUE.start(step(6.0, 4.0), self).apply(self);
        assertThat(result(a).reached()).isTrue();
    }

    @Test
    void aMoverKeepsItsOwnRadiusClearOfThePillar() {
        assertThat(contact(withRadius(0.3), 2.0, 4.0).time()).isCloseTo(4.4, within(1e-12));
    }

    @Test
    void theBoundaryIsTouchedByAMoverWithARadiusAtItsEdge() {
        Contacts.Contact c = contact(withRadius(0.5), 9.8, 4.0);
        assertThat(c.other()).isNull();
        assertThat(c.time()).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void aMoverOnThePillarsEdgeCannotStepInButCanLeave() {
        Entity touching = TestArea.orbiter().withPosition(new Position(5.5, 4.0));
        assertThatThrownBy(() -> run(touching, 2.0, 4.0)).isInstanceOf(InvalidAction.class)
                .hasMessage("step: to: the step starts touching " + PILLAR + ", which it moves toward");
        assertThat(result(run(touching, 7.0, 4.0)).reached()).isTrue();
    }

    @Test
    void aMoverIgnoresItself() {
        Applied a = run(withRadius(0.5), 8.0, 6.0);
        assertThat(result(a).reached()).isTrue();
    }

    @Test
    void anInterruptedStepStaysWhereItIsAndSaysSo() {
        Entity halfway = TestArea.orbiter().withPosition(new Position(6.0, 4.0));
        Actor self = TestArea.actor(ORBITER, halfway, Map.of(PILLAR, TestArea.pillar()));
        Applied a = CATALOGUE.start(step(2.0, 4.0), self).interrupt(self);
        assertThat(a.entity().position()).isEqualTo(new Position(6.0, 4.0));
        assertThat(WorldJson.toTree(a.result())).isEqualTo(TestArea.tree("""
                {"to":{"x":2.0,"y":4.0},"reached":false,"stoppedAt":{"x":6.0,"y":4.0}}"""));
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
        Entity still = TestArea.state("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"stone","name":"stone","position":{"x":8.0,"y":4.0},"actions":["step"]}""");
        assertThatThrownBy(() -> run(still, 8.0, 6.0))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step: speed: the entity has none, so it can't step");
    }
}
