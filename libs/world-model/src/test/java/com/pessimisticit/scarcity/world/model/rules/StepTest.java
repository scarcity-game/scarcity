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

    /** The room's east wall, from x 10 to 10.5. */
    private static final String EAST = "entity:00000000-0000-4000-8000-0000000000c2";

    /** The first contact of a step by {@code mover} to (x, y) with the pillar or the room's walls. */
    private static Contacts.Contact contact(Entity mover, double x, double y) {
        Actor self = TestArea.actor(ORBITER, mover, Map.of(PILLAR, TestArea.pillar()));
        Motion motion = CATALOGUE.start(step(x, y), self).motion(self).orElseThrow();
        return Contacts.first(self.areaView(), Map.of(), ORBITER, motion);
    }

    @Test
    void thePillarIsTouchedAtItsEdge() {
        Contacts.Contact c = contact(TestArea.orbiter().withSpeed(1.0), 3.0, 4.0);
        assertThat(c.other()).isEqualTo(PILLAR);
        assertThat(c.time()).as("from x 8.0 to the edge at 5.5, at speed 1.0").isCloseTo(2.5, within(1e-12));
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
        assertThat(contact(withRadius(0.3).withSpeed(1.0), 3.0, 4.0).time()).isCloseTo(2.2, within(1e-12));
    }

    @Test
    void theRoomsWallIsTouchedByAMoverWithARadiusAtItsEdge() {
        Contacts.Contact c = contact(withRadius(0.5), 9.8, 4.0);
        assertThat(c.other()).isEqualTo(EAST);
        assertThat(c.time()).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void aMoverOnThePillarsEdgeCannotStepInButCanLeave() {
        Entity touching = TestArea.orbiter().withPosition(new Position(5.5, 4.0));
        assertThatThrownBy(() -> run(touching, 3.5, 4.0)).isInstanceOf(InvalidAction.class)
                .hasMessage("step: to: the step starts touching " + PILLAR + ", which it moves toward");
        assertThat(result(run(touching, 7.0, 4.0)).reached()).isTrue();
    }

    /** The first contact of a step by {@code mover} to (x, y) with a wall from x 9.0 to 9.5. */
    private static Contacts.Contact wallContact(Entity mover, double x, double y) {
        Actor self = TestArea.actor(ORBITER, mover, Map.of(TestArea.WALL, TestArea.wall(9.0, 1.0, 9.5, 7.0)));
        Motion motion = CATALOGUE.start(step(x, y), self).motion(self).orElseThrow();
        return Contacts.first(self.areaView(), Map.of(), ORBITER, motion);
    }

    @Test
    void aWallStopsAMoverItsRadiusShortOfItsFace() {
        Contacts.Contact c = wallContact(withRadius(0.3), 9.8, 4.0);
        assertThat(c.other()).isEqualTo(TestArea.WALL);
        assertThat(c.time()).as("from x 8.0 to 8.7, at speed 0.5").isCloseTo(1.4, within(1e-12));
    }

    @Test
    void aWallsCornerIsMetAsACircleOfTheMoversRadius() {
        // Up along x 8.8, 0.2 left of the wall: its corner (9.0, 1.0) is met where it is 0.3 away.
        Entity below = withRadius(0.3).withPosition(new Position(8.8, 0.5));
        double dy = Math.sqrt(0.3 * 0.3 - 0.2 * 0.2);
        assertThat(wallContact(below, 8.8, 3.0).time()).isCloseTo((0.5 - dy) / 0.5, within(1e-12));
    }

    @Test
    void aMoverTouchingAWallCannotStepIntoItButCanLeave() {
        Entity touching = withRadius(0.3).withPosition(new Position(8.7, 4.0));
        Actor self = TestArea.actor(ORBITER, touching, Map.of(TestArea.WALL, TestArea.wall(9.0, 1.0, 9.5, 7.0)));
        assertThatThrownBy(() -> CATALOGUE.start(step(9.8, 4.0), self)).isInstanceOf(InvalidAction.class)
                .hasMessage("step: to: the step starts touching " + TestArea.WALL + ", which it moves toward");
        Applied away = CATALOGUE.start(step(7.0, 4.0), self).apply(self);
        assertThat(result(away).reached()).isTrue();
    }

    @Test
    void anEntityWithAPolygonCannotStep() {
        Entity block = TestArea.wall(1.0, 1.0, 2.0, 2.0).withSpeed(1.0).withActions(java.util.List.of("step"));
        Actor self = TestArea.actor(ORBITER, block, Map.of());
        assertThatThrownBy(() -> CATALOGUE.start(step(5.0, 5.0), self)).isInstanceOf(InvalidAction.class)
                .hasMessageContaining("polygon: only a circle can step");
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
        Applied a = CATALOGUE.start(step(4.0, 4.0), self).interrupt(self);
        assertThat(a.entity().position()).isEqualTo(new Position(6.0, 4.0));
        assertThat(WorldJson.toTree(a.result())).isEqualTo(TestArea.tree("""
                {"to":{"x":4.0,"y":4.0},"reached":false,"stoppedAt":{"x":6.0,"y":4.0}}"""));
    }

    @Test
    void aStepGoesAtMostSpeedTimesFiveSecondsSoAFasterEntityGoesFurther() {
        // At 0.5 m/s the reach is 2.5 m: to x 5.5 is exactly that, to x 5.4 is past it.
        assertThat(Step.reach(TestArea.orbiter())).isEqualTo(2.5);
        assertThat(result(run(TestArea.orbiter(), 8.0, 6.5)).reached()).isTrue();
        assertThatThrownBy(() -> run(TestArea.orbiter(), 8.0, 6.6))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("step: to: 2.5999999999999996 m away, beyond the entity's reach of 2.5 m")
                .hasMessageEndingWith("(speed 0.5 m/s x 5.0 s)");
        assertThat(result(run(TestArea.orbiter().withSpeed(1.0), 8.0, 6.6)).reached()).isTrue();
    }

    @Test
    void aStepAimedAtExactlyTheReachIsNotRefusedForARoundingError() {
        // The diver aims at min(max-distance, reach) along a diagonal: 2.5000000000000004 m in the wild.
        double over = Math.nextUp(Math.nextUp(2.5));
        assertThat(over).isGreaterThan(2.5);
        assertThat(result(run(TestArea.orbiter(), 8.0, 4.0 + over)).reached()).isTrue();
    }

    @Test
    void aZeroLengthStepIsInvalid() {
        assertThatThrownBy(() -> run(TestArea.orbiter(), 8.0, 4.0))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step: to: a zero-length step (it is the entity's position)");
    }

    @Test
    void aStepPastTheWallIsValidAndStopsAtIt() {
        // To x 10.5, beyond the east wall's face at x 10: valid, and the wall is met after 2 m.
        Contacts.Contact c = contact(TestArea.orbiter(), 10.5, 4.0);
        assertThat(c.other()).isEqualTo(EAST);
        assertThat(c.time()).isCloseTo(4.0, within(1e-12));
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
