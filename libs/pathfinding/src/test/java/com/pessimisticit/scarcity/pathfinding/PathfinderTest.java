package com.pessimisticit.scarcity.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Paths in a 10 x 8 room enclosed by four granite walls, for a diver of radius 0.3. */
class PathfinderTest {

    static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    static final String DIVER = "entity:00000000-0000-4000-8000-000000000001";
    private static final double RADIUS = 0.3;

    static Entity diverAt(double x, double y) {
        return WorldJson.fromJson("""
                {"resourceKind":"entity","entityKind":"npc","prototype":"diver","name":"diver",
                 "position":{"x":%s,"y":%s},"radius":%s,"speed":1.0,"actions":["step"]}""".formatted(x, y, RADIUS),
                Entity.class);
    }

    static Entity wall(double x0, double y0, double x1, double y1) {
        return Materials.get("granite").wall(List.of(new Position(x0, y0), new Position(x1, y0),
                new Position(x1, y1), new Position(x0, y1)));
    }

    static Entity pillar(double x, double y) {
        return WorldJson.fromJson("""
                {"resourceKind":"entity","entityKind":"fixed","prototype":"pillar","name":"pillar",
                 "position":{"x":%s,"y":%s},"radius":0.5,"overlap":false}""".formatted(x, y), Entity.class);
    }

    /**
     * The room's walls, then {@code others} by id ("entity:…0b1", "…0b2", …), and the diver, over
     * the room's cells ({@link Grid}: lines along every wall, no cell over 1 m a side).
     */
    static AreaView room(Entity diver, Entity... others) {
        AreaView bare = bare(diver, others);
        List<Entity> walls = bare.entities().values().stream()
                .filter(e -> e.prototype().equals("wall")).toList();
        return AreaView.of(AREA, bare.area(), bare.entities(), Grid.cells(walls, 1.0));
    }

    /** {@link #room} without cells, as an area made before they were kept (#353). */
    static AreaView bare(Entity diver, Entity... others) {
        Map<String, Entity> entities = new HashMap<>();
        entities.put("entity:00000000-0000-4000-8000-0000000000c1", wall(-0.5, -0.5, 10.5, 0.0));
        entities.put("entity:00000000-0000-4000-8000-0000000000c2", wall(10.0, -0.5, 10.5, 8.5));
        entities.put("entity:00000000-0000-4000-8000-0000000000c3", wall(-0.5, 8.0, 10.5, 8.5));
        entities.put("entity:00000000-0000-4000-8000-0000000000c4", wall(-0.5, -0.5, 0.0, 8.5));
        for (int i = 0; i < others.length; i++) {
            entities.put("entity:00000000-0000-4000-8000-0000000000b" + (i + 1), others[i]);
        }
        entities.put(DIVER, diver);
        return AreaView.of(AREA, new Area("room", 0.0), entities);
    }

    /** Every leg, taken at the diver's own radius from its start, ends before anything interrupts it. */
    static void assertEveryLegIsClear(AreaView view, List<Position> path) {
        Position at = view.peek(DIVER).position();
        for (Position next : path) {
            double length = at.distanceTo(next);
            Map<String, Entity> moved = new HashMap<>(view.entities());
            moved.put(DIVER, view.peek(DIVER).withPosition(at));
            Motion along = new Motion((next.x() - at.x()) / length, (next.y() - at.y()) / length);
            double t = Contacts.first(AreaView.of(AREA, view.area(), moved), Map.of(), DIVER, along).time();
            assertThat(t).as("leg " + at + " to " + next).isGreaterThanOrEqualTo(length);
            at = next;
        }
    }

    private static double length(Position from, List<Position> path) {
        double total = 0.0;
        Position at = from;
        for (Position p : path) {
            total += at.distanceTo(p);
            at = p;
        }
        return total;
    }

    @Test
    void inTheOpenThePathIsTheTargetAlone() {
        AreaView view = room(diverAt(2.0, 2.0));
        assertThat(Pathfinder.path(view, DIVER, new Position(8.0, 6.0))).contains(List.of(new Position(8.0, 6.0)));
        assertThat(Pathfinder.path(view, DIVER, new Position(2.0, 2.0))).contains(List.of());
    }

    @Test
    void aWallMadeOfCellsThatMeetIsGoneAroundLikeOneWall() {
        // The same wall as below, as 0.4 m by 0.5 m cells sharing their edges (#234): the corners
        // where they meet aren't bends, and the path is the one round the single wall.
        Entity[] cells = new Entity[13];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = wall(4.8, 1.5 + 0.5 * i, 5.2, 2.0 + 0.5 * i);
        }
        AreaView view = room(diverAt(2.0, 6.0), cells);
        Position to = new Position(8.0, 6.0);

        List<Position> path = Pathfinder.path(view, DIVER, to).orElseThrow();

        assertThat(path).isEqualTo(Pathfinder.path(room(diverAt(2.0, 6.0), wall(4.8, 1.5, 5.2, 8.0)), DIVER, to)
                .orElseThrow());
        assertEveryLegIsClear(view, path);
    }

    @Test
    void aWallInTheWayIsGoneAroundAndNoLegCrossesIt() {
        // A wall across the room from y 1.5 to 8 at x 5: the way round is below it.
        AreaView view = room(diverAt(2.0, 6.0), wall(4.8, 1.5, 5.2, 8.0));
        Position to = new Position(8.0, 6.0);

        List<Position> path = Pathfinder.path(view, DIVER, to).orElseThrow();

        assertThat(path.getLast()).isEqualTo(to);
        assertThat(path).hasSizeGreaterThan(1);
        assertThat(path).as("round the wall's lower end").anyMatch(p -> p.y() < 1.5 - RADIUS);
        assertEveryLegIsClear(view, path);
    }

    @Test
    void anLShapedPairOfOverlappingWallsIsGoneAround() {
        // An L, open toward the lower left: the diver stands in its inner corner, the target is behind it.
        Entity across = wall(3.0, 5.0, 7.0, 5.5);
        Entity down = wall(6.5, 1.5, 7.0, 5.5);
        AreaView view = room(diverAt(6.0, 4.5), across, down);
        Position to = new Position(8.5, 6.5);

        List<Position> path = Pathfinder.path(view, DIVER, to).orElseThrow();

        assertThat(path.getLast()).isEqualTo(to);
        assertEveryLegIsClear(view, path);
        double straight = new Position(6.0, 4.5).distanceTo(to);
        assertThat(length(new Position(6.0, 4.5), path)).isGreaterThan(straight);
    }

    @Test
    void aGapNarrowerThanTheDiverIsBlockedAndAWiderOneIsPassable() {
        // A wall from wall to wall at x 5, with a gap in it from y 4 to 4 + gap.
        Position to = new Position(8.0, 4.0);
        AreaView narrow = room(diverAt(2.0, 4.0), wall(4.8, 0.0, 5.2, 4.0), wall(4.8, 4.5, 5.2, 8.0));
        assertThat(Pathfinder.path(narrow, DIVER, to)).as("0.5 < 2 x 0.3").isEmpty();

        AreaView wide = room(diverAt(2.0, 4.0), wall(4.8, 0.0, 5.2, 4.0), wall(4.8, 4.8, 5.2, 8.0));
        List<Position> path = Pathfinder.path(wide, DIVER, to).orElseThrow();
        assertThat(path.getLast()).isEqualTo(to);
        assertEveryLegIsClear(wide, path);
    }

    @Test
    void aTargetWalledInIsUnreachable() {
        AreaView view = room(diverAt(2.0, 2.0), wall(6.0, 3.0, 9.0, 3.5), wall(6.0, 6.0, 9.0, 6.5),
                wall(6.0, 3.0, 6.5, 6.5), wall(8.5, 3.0, 9.0, 6.5));
        assertThat(Pathfinder.path(view, DIVER, new Position(7.5, 4.75))).isEmpty();
    }

    @Test
    void aPillarInTheWayIsGoneAround() {
        AreaView view = room(diverAt(2.0, 4.0), pillar(5.0, 4.0));
        List<Position> path = Pathfinder.path(view, DIVER, new Position(8.0, 4.0)).orElseThrow();
        assertThat(path).hasSizeGreaterThan(1);
        assertEveryLegIsClear(view, path);
    }

    @Test
    void aDiverTouchingAWallCanLeaveIt() {
        // Interrupted against the wall at x 5 last turn: its edge is on the wall's face.
        AreaView view = room(diverAt(4.8 - RADIUS, 6.0), wall(4.8, 1.5, 5.2, 8.0));
        List<Position> path = Pathfinder.path(view, DIVER, new Position(8.0, 6.0)).orElseThrow();
        assertEveryLegIsClear(view, path);
    }

    @Test
    void theSameAreaGivesTheSamePathWhateverTheOrderOfItsEntities() {
        AreaView view = room(diverAt(2.0, 6.0), wall(4.8, 1.5, 5.2, 8.0), pillar(7.0, 3.0));
        Map<String, Entity> reversed = new LinkedHashMap<>();
        List<String> ids = List.copyOf(view.entities().keySet()).reversed();
        ids.forEach(id -> reversed.put(id, view.peek(id)));
        AreaView again = AreaView.of(AREA, view.area(), reversed, view.cells());
        Position to = new Position(8.0, 6.0);

        Optional<List<Position>> path = Pathfinder.path(view, DIVER, to);

        assertThat(path).isPresent();
        assertThat(Pathfinder.path(again, DIVER, to)).isEqualTo(path);
        assertThat(Pathfinder.path(view, DIVER, to)).isEqualTo(path);
    }
}
