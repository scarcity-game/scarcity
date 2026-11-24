package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.engine.LogEntry.Kind;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A channel (docs/actions.md, "Actions"): a step moves on before every event
 * while it runs, so others see the mover where it has got to, and it ends
 * early when something comes into its way.
 */
class ChannelTest {

    private static final String BLOCKER = "entity:00000000-0000-4000-8000-00000000000a";

    private static Store room() {
        return Room.walled(10.0).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter());
    }

    private static List<LogEntry> of(AdvanceResult r, Kind kind, String resource) {
        return r.log().stream().filter(e -> e.kind() == kind && resource.equals(e.resource())).toList();
    }

    private static Position at(LogEntry e) {
        return ((Entity) e.changes().getFirst().state()).position();
    }

    @Test
    void aStepWithNothingElseHappeningIsAStartAndAnEnd() {
        AdvanceResult r = room().advance(Room.intent(Room.stepTo(8.0, 6.0)), new ScriptedRandom());

        assertThat(r.log().stream().map(LogEntry::kind)).containsExactly(Kind.INTENT, Kind.ACTION_START, Kind.ACTION_END);
        assertThat(r.log().get(1).actionInProgress().timing()).isEqualTo(Timing.CHANNEL);
        assertThat(r.area().time()).isEqualTo(14.0);
    }

    @Test
    void aStepMovesOnAtEveryEventWhileItRunsAndIsWrittenOnce() {
        Store store = room().put(RAT, Room.rat(Room.repeatWait(1.0)));
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(8.0, 6.0)), new ScriptedRandom());

        List<LogEntry> progress = of(r, Kind.ACTION_PROGRESS, ORBITER);
        assertThat(progress).extracting(LogEntry::time).containsExactly(11.0, 12.0, 13.0);
        assertThat(progress).extracting(ChannelTest::at).containsExactly(
                new Position(8.0, 4.5), new Position(8.0, 5.0), new Position(8.0, 5.5));
        LogEntry start = of(r, Kind.ACTION_START, ORBITER).getFirst();
        assertThat(progress).allMatch(e -> e.cause() == start.seq() && e.root() == start.root());
        assertThat(progress.get(1).result()).isEqualTo(Room.object("""
                {"from":{"x":8.0,"y":4.5},"at":{"x":8.0,"y":5.0},"distance":0.5}"""));
        assertThat(of(r, Kind.ACTION_END, ORBITER).getFirst().time()).isEqualTo(14.0);
        assertThat(store.state(ORBITER).position()).isEqualTo(new Position(8.0, 6.0));
        assertThat(r.writes().stream().filter(w -> w.id().equals(ORBITER))).hasSize(1);
    }

    private static final String MOB = """
            {"resourceKind":"entity","entityKind":"mob","prototype":"rat","name":"blocker","position":{"x":%s,"y":%s},
             "speed":1.0,"radius":0.5,"actions":["step"],
             "behaviour":{"kind":"repeat","actionSpec":{"type":"step","params":{"to":{"x":%s,"y":%s}}}}}""";

    private static Entity mob(double x, double y, double toX, double toY) {
        return Room.state(MOB.formatted(x, y, toX, toY));
    }

    @Test
    void somethingThatMovesIntoTheWayInterruptsTheStepWhenItsEdgeIsReached() {
        Store store = room().put(RAT, Room.rat(Room.repeatWait(1.0))).put(BLOCKER, mob(6.0, 6.0, 8.0, 6.0));
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(8.0, 6.5)), new ScriptedRandom());

        LogEntry contact = r.log().stream().filter(e -> e.kind() == Kind.CONTACT).findFirst().orElseThrow();
        assertThat(contact.time()).as("exactly when the edges touch, not at the next event").isEqualTo(13.0);
        assertThat(contact.result().get("resources")).hasSize(2);
        assertThat(contact.result().get("distance").asDouble()).isEqualTo(0.5);
        LogEntry interrupted = of(r, Kind.ACTION_INTERRUPTED, ORBITER).getFirst();
        assertThat(interrupted.cause()).isEqualTo(contact.seq());
        assertThat(interrupted.time()).isEqualTo(13.0);
        assertThat(interrupted.result()).isEqualTo(Room.object("""
                {"to":{"x":8.0,"y":6.5},"reached":false,"stoppedAt":{"x":8.0,"y":5.5}}"""));
        assertThat(of(r, Kind.ACTION_END, ORBITER)).as("an interrupted action does not end").isEmpty();
        assertThat(of(r, Kind.ACTION_INTERRUPTED, BLOCKER)).as("the blocker was not moving").isEmpty();
        assertThat(store.state(ORBITER).position()).isEqualTo(new Position(8.0, 5.5));
        assertThat(r.area().time()).isEqualTo(13.0);
    }

    @Test
    void aWallInterruptsTheStepAtTheMoversRadiusAndIsLoggedAsTheContact() {
        String wall = "entity:00000000-0000-4000-8000-0000000000b1";
        Entity stone = com.pessimisticit.scarcity.world.model.Materials.get("granite").wall(List.of(
                new Position(7.0, 6.0), new Position(9.0, 6.0), new Position(9.0, 6.5), new Position(7.0, 6.5)));
        Store store = room().put(ORBITER, Room.orbiter().withRadius(0.3)).put(wall, stone);
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(8.0, 6.5)), new ScriptedRandom());

        LogEntry contact = r.log().stream().filter(e -> e.kind() == Kind.CONTACT).findFirst().orElseThrow();
        assertThat(contact.time()).as("from y 4.0 to 5.7, at speed 0.5").isCloseTo(13.4, within(1e-9));
        assertThat(contact.result().get("resources").get(1).asText()).isEqualTo(wall);
        assertThat(contact.result().get("distance").asDouble()).as("the mover's radius").isEqualTo(0.3);
        assertThat(of(r, Kind.ACTION_INTERRUPTED, ORBITER).getFirst().cause()).isEqualTo(contact.seq());
        assertThat(store.state(ORBITER).position().y()).isCloseTo(5.7, within(1e-9));
    }

    @Test
    void twoMoversThatMeetAreBothInterruptedAtTheContact() {
        Store store = room().put(BLOCKER, mob(8.0, 7.0, 8.0, 5.0));
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(8.0, 6.5)), new ScriptedRandom());

        LogEntry contact = r.log().stream().filter(e -> e.kind() == Kind.CONTACT).findFirst().orElseThrow();
        assertThat(contact.time()).isCloseTo(10.0 + 5.0 / 3.0, within(1e-9));
        assertThat(contact.resource()).as("the lower id of the pair").isEqualTo(ORBITER);
        assertThat(of(r, Kind.ACTION_INTERRUPTED, ORBITER)).singleElement()
                .satisfies(e -> assertThat(e.cause()).isEqualTo(contact.seq()));
        assertThat(of(r, Kind.ACTION_INTERRUPTED, BLOCKER)).singleElement()
                .satisfies(e -> assertThat(e.cause()).isEqualTo(contact.seq()));
        double gap = store.state(BLOCKER).position().y() - store.state(ORBITER).position().y();
        assertThat(gap).as("their edges touch").isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aStepIntoTheRoomsWallIsInterruptedThere() {
        Store store = room();
        store.put(ORBITER, store.state(ORBITER).withRadius(0.5));
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(9.8, 4.0)), new ScriptedRandom());

        LogEntry contact = r.log().stream().filter(e -> e.kind() == Kind.CONTACT).findFirst().orElseThrow();
        assertThat(contact.result().get("resources").get(1).asString()).isEqualTo(Room.EAST_WALL);
        assertThat(store.state(ORBITER).position().x()).isCloseTo(9.5, within(1e-9));
    }
}
