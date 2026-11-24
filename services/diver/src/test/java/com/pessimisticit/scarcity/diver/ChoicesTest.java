package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Step;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Against a stub world's area: exactly one choice is feasible on every turn. Step along the way to the
 * stairs down (never the stairs up) while out of reach, round what is in the way, traverse them
 * within reach, wait if there are none or there is no way.
 */
class ChoicesTest {

    private static final DiverProperties SETTINGS = new DiverProperties(
            Duration.ZERO, new DiverProperties.StepSettings(1.0), new DiverProperties.WaitSettings(1.0));

    private final StubWorld stubWorld;
    private final World world;
    private final List<Choice> choices;
    private long version;
    private Choice.Situation current;

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
        current = null;
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

    /** The turn as Player gives it to the choices: the area's entities read once, the diver as it is. */
    private Choice.Situation situation(Entity self) {
        if (current == null) {
            Map<String, Entity> entities = new LinkedHashMap<>();
            world.entities(StubWorld.AREA).forEach(e -> entities.put(e.id(), e));
            entities.put(self.id(), self);
            AreaView view = AreaView.of(StubWorld.AREA, new Area(StubWorld.AREA, 0.0), entities);
            current = new Choice.Situation(turn(), self, view, world);
        }
        return current;
    }

    private List<String> open(Entity self) {
        return choices.stream().filter(c -> c.plan(situation(self)).feasible()).map(Choice::name).toList();
    }

    @Test
    void outOfReachItStepsAtMostTheMaxDistanceTowardTheStairsDown() {
        Entity self = level(4.0, 4.0, true);
        assertThat(open(self)).containsExactly("step");

        Choice.Plan plan = choices.getFirst().plan(situation(self));
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
        Position to = WorldJson.fromJson(choices.getFirst().plan(situation(self)).actionSpec().params(),
                Step.Params.class).to();
        assertThat(to.distanceTo(new Position(1.5, 1.5))).isCloseTo(ApproachChoice.STOP_SHORT, offset(1e-9));
        assertThat(ApproachChoice.STOP_SHORT).isLessThan(Traverse.REACH);
    }

    @Test
    void withinReachItTakesTheStairsDown() {
        Entity self = level(2.0, 1.5, true);
        assertThat(open(self)).containsExactly("traverse");
        Choice.Plan plan = choices.get(1).plan(situation(self));
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

    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000099";

    private static String mouse(double x, double y) {
        return "{\"entityKind\":\"npc\",\"prototype\":\"mouse\",\"name\":\"mouse\","
                + "\"position\":{\"x\":" + x + ",\"y\":" + y + "},\"speed\":0.2,\"radius\":0.1,\"hp\":1.0,"
                + "\"damageable\":true}";
    }

    private Entity levelWithMouse(double x, double y) {
        Entity self = level(4.0, 4.0, true);
        stubWorld.entity(MOUSE, 1, mouse(x, y));
        stubWorld.children(StubWorld.AREA,
                List.of(Fixtures.STAIRS_UP, Fixtures.STAIRS_DOWN, Fixtures.DIVER, MOUSE));
        return self;
    }

    @Test
    void touchingAMouseBetweenItAndTheStairsItStepsRoundIt() {
        // radii 0.3 + 0.1; the mouse 0.4 away on the line from (4, 4) to (1.5, 1.5)
        double d = 0.4 / Math.sqrt(2);
        Entity self = levelWithMouse(4.0 - d, 4.0 - d);
        assertThat(open(self)).containsExactly("step");
        Position to = WorldJson.fromJson(choices.getFirst().plan(situation(self)).actionSpec().params(),
                Step.Params.class).to();
        Position mouse = new Position(4.0 - d, 4.0 - d);
        assertThat(to.distanceTo(mouse)).as("not straight through it").isGreaterThan(0.4);
    }

    private Entity levelWithWall(String id, double x0, double y0, double x1, double y1) {
        Entity self = level(4.0, 4.0, true);
        stubWorld.entity(id, 1, wall(x0, y0, x1, y1));
        List<String> children = new java.util.ArrayList<>(List.of(Fixtures.STAIRS_UP, Fixtures.STAIRS_DOWN,
                Fixtures.DIVER));
        children.add(id);
        stubWorld.children(StubWorld.AREA, children);
        return self;
    }

    private static String wall(double x0, double y0, double x1, double y1) {
        return WorldJson.toJson(com.pessimisticit.scarcity.world.model.Prototypes.get("stone-wall").withPolygon(List.of(
                new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1))));
    }

    @Test
    void aWallBetweenItAndTheStairsIsGoneRound() {
        // A wall across the straight line from (4, 4) to the stairs down at (1.5, 1.5).
        Entity self = levelWithWall(WALL + "0", 1.0, 2.6, 3.2, 3.0);
        assertThat(open(self)).containsExactly("step");
        Position to = WorldJson.fromJson(choices.getFirst().plan(situation(self)).actionSpec().params(),
                Step.Params.class).to();
        Position straight = ApproachChoice.toward(new Position(4.0, 4.0), new Position(1.5, 1.5),
                ApproachChoice.STOP_SHORT, SETTINGS.stepSettings().maxDistance());
        assertThat(to).as("not straight at the stairs, which is through the wall").isNotEqualTo(straight);
        assertThat(to.x()).as("round the wall's right-hand end").isGreaterThan(3.2);
    }

    @Test
    void stairsWalledInMakeItWait() {
        // A box round the stairs down at (1.5, 1.5), from (0.5, 0.5) to (2.5, 2.5).
        Entity self = level(4.0, 4.0, true);
        List<String> children = new java.util.ArrayList<>(List.of(Fixtures.STAIRS_UP, Fixtures.STAIRS_DOWN,
                Fixtures.DIVER));
        String[] ids = {WALL + "1", WALL + "2", WALL + "3", WALL + "4"};
        double[][] box = {{0.5, 0.5, 2.5, 0.7}, {0.5, 2.3, 2.5, 2.5}, {0.5, 0.5, 0.7, 2.5}, {2.3, 0.5, 2.5, 2.5}};
        for (int i = 0; i < ids.length; i++) {
            stubWorld.entity(ids[i], 1, wall(box[i][0], box[i][1], box[i][2], box[i][3]));
            children.add(ids[i]);
        }
        stubWorld.children(StubWorld.AREA, children);
        assertThat(open(self)).containsExactly("wait");
    }

    private static final String WALL = "entity:00000000-0000-4000-8000-0000000000b";

    @Test
    void touchingAMouseBehindItItStillSteps() {
        double d = 0.4 / Math.sqrt(2);
        Entity self = levelWithMouse(4.0 + d, 4.0 + d);
        assertThat(open(self)).containsExactly("step");
    }

    @Test
    void aMouseNearButNotTouchingItStillSteps() {
        Entity self = levelWithMouse(3.0, 3.0);
        assertThat(open(self)).containsExactly("step");
    }

    @Test
    void theWayIsPlannedOncePerTurn() {
        Entity self = level(4.0, 4.0, true);
        Stairway stairway = new Stairway();
        assertThat(stairway.route(situation(self))).isSameAs(stairway.route(situation(self)));
    }
}
