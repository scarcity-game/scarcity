package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

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
        return new Store(Room.area(10.0)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter());
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
                {"from":{"x":8.0,"y":4.5},"at":{"x":8.0,"y":5.0},"distance":0.5,"blocked":false}"""));
        assertThat(of(r, Kind.ACTION_END, ORBITER).getFirst().time()).isEqualTo(14.0);
        assertThat(store.state(ORBITER).position()).isEqualTo(new Position(8.0, 6.0));
        assertThat(r.writes().stream().filter(w -> w.id().equals(ORBITER))).hasSize(1);
    }

    @Test
    void somethingThatMovesIntoTheWayStopsTheStepAtItsEdgeAndItEndsThen() {
        Store store = room().put(RAT, Room.rat(Room.repeatWait(1.0))).put(BLOCKER, Room.state("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"rat","name":"blocker","position":{"x":6.0,"y":6.0},
                 "speed":1.0,"radius":0.5,"actions":["step"],
                 "behaviour":{"kind":"repeat","actionSpec":{"type":"step","params":{"to":{"x":8.0,"y":6.0}}}}}"""));
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(8.0, 7.0)), new ScriptedRandom());

        LogEntry stop = of(r, Kind.ACTION_PROGRESS, ORBITER).getLast();
        assertThat(stop.time()).isEqualTo(14.0);
        assertThat(stop.result().get("blocked").asBoolean()).isTrue();
        assertThat(stop.actionInProgress().end()).as("it ends when it is stopped, not at 16.0").isEqualTo(14.0);
        LogEntry end = of(r, Kind.ACTION_END, ORBITER).getFirst();
        assertThat(end.time()).isEqualTo(14.0);
        assertThat(end.result()).isEqualTo(Room.object("""
                {"to":{"x":8.0,"y":7.0},"reached":false,"stoppedAt":{"x":8.0,"y":5.5}}"""));
        assertThat(store.state(ORBITER).position()).isEqualTo(new Position(8.0, 5.5));
        assertThat(r.area().time()).isEqualTo(14.0);
    }
}
