package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.engine.LogEntry.Kind;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The stop rule, chaining and one write per resource (docs/actions.md, "Game time" and "Actions"). */
class AdvanceTest {

    private static Store room(Entity rat) {
        Store store = new Store(Room.area(10.0)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter());
        return rat == null ? store : store.put(RAT, rat);
    }

    private static List<Kind> kinds(AdvanceResult r) {
        return r.log().stream().map(LogEntry::kind).toList();
    }

    @Test
    void theAdvanceStopsWhenTheControlledEntityIsIdleAgain() {
        Store store = room(null);
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.area().time()).isEqualTo(12.0);
        assertThat(kinds(r)).containsExactly(Kind.INTENT, Kind.ACTION_START, Kind.ACTION_END);
        LogEntry intent = r.log().get(0);
        LogEntry start = r.log().get(1);
        LogEntry end = r.log().get(2);
        assertThat(intent.controller()).isEqualTo(Room.ORBITAL);
        assertThat(intent.actionSpec()).isEqualTo(Room.waitFor(2.0));
        assertThat(start.cause()).isEqualTo(intent.seq());
        assertThat(start.actionInProgress()).isEqualTo(new ActionInProgress("wait", Room.object("{\"duration\":2.0}"),
                10.0, 12.0, Timing.NONE, start.seq(), null));
        assertThat(end.cause()).isEqualTo(start.seq());
        assertThat(end.root()).isEqualTo(intent.seq());
        assertThat(r.writes()).containsExactly(new Write(ORBITER, Write.Op.CHANGED, 2L, Room.orbiter().withId(ORBITER).withVersion(2)));
        assertThat(r.areaVersion()).isEqualTo(6);
        assertThat(store.state(ORBITER).idle()).isTrue();
    }

    @Test
    void aStepEndsWithItsResultAndTheNewPosition() {
        Store store = room(null);
        AdvanceResult r = store.advance(Room.intent(Room.stepTo(9.0, 4.0)), new ScriptedRandom());

        assertThat(r.area().time()).isEqualTo(12.0);
        assertThat(store.state(ORBITER).position().x()).isEqualTo(9.0);
        assertThat(r.log().getLast().result()).isEqualTo(Room.object("""
                {"to":{"x":9.0,"y":4.0},"reached":true,"stoppedAt":{"x":9.0,"y":4.0}}"""));
    }

    @Test
    void chainedActionsStartAtTheEndBeforeAndAreWrittenOnce() {
        Store store = room(Room.rat(Room.repeatWait(0.3)));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());

        List<LogEntry> ratStarts = r.log().stream()
                .filter(e -> e.kind() == Kind.ACTION_START && RAT.equals(e.resource())).toList();
        assertThat(ratStarts).hasSize(4);
        for (int i = 1; i < ratStarts.size(); i++) {
            ActionInProgress before = ratStarts.get(i - 1).actionInProgress();
            assertThat(ratStarts.get(i).actionInProgress().start()).isEqualTo(before.end());
            assertThat(ratStarts.get(i).cause()).as("a behaviour's start has no cause").isNull();
            assertThat(ratStarts.get(i).draws()).as("a repeat draws nothing").isEmpty();
        }
        assertThat(r.writes().stream().filter(w -> w.id().equals(RAT))).hasSize(1);
        assertThat(r.log().stream().flatMap(e -> e.changes().stream()).filter(c -> c.id().equals(RAT)))
                .hasSize(7)
                .allMatch(c -> c.version() == 2L);
    }

    @Test
    void anNpcWithARandomBehaviourKeepsActingWhileTheControlledActionRuns() {
        Store store = room(Room.rat("""
                {"kind":"random","choices":[{"weight":1.0,"actionSpec":%s},{"weight":3.0,"actionSpec":%s}]}"""
                .formatted(Room.waitJson(0.5), Room.waitJson(1.0))));
        ScriptedRandom rng = new ScriptedRandom().uniform(0.1, 0.9, 0.2, 0.3);
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), rng);

        List<LogEntry> ratStarts = r.log().stream()
                .filter(e -> e.kind() == Kind.ACTION_START && RAT.equals(e.resource())).toList();
        assertThat(ratStarts).extracting(e -> e.actionInProgress().start()).containsExactly(10.0, 10.5, 11.5, 12.0);
        assertThat(ratStarts.getFirst().draws()).containsExactly(
                new LoggedDraw("behaviour", new Draw.Categorical(List.of(1.0, 3.0), 0.1, 0)));
        assertThat(ratStarts.getFirst().actionInProgress().draw()).as("the entry's action has no draw").isNull();
        assertThat(store.state(RAT).actionInProgress().draw()).as("the state's does")
                .isEqualTo(new Draw.Categorical(List.of(1.0, 3.0), 0.3, 1));
        assertThat(rng.exhausted()).isTrue();
    }

    @Test
    void eventsAfterTheStopWaitForTheNextAdvance() {
        Store store = room(Room.rat(Room.repeatWait(5.0)));
        store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());

        assertThat(store.area().time()).isEqualTo(11.0);
        ActionInProgress rat = store.state(RAT).actionInProgress();
        assertThat(rat.start()).isEqualTo(10.0);
        assertThat(rat.end()).isEqualTo(15.0);

        AdvanceResult next = store.advance(Room.intent(Room.waitFor(5.0)), new ScriptedRandom());
        assertThat(next.area().time()).isEqualTo(16.0);
        LogEntry ratEnd = next.log().stream()
                .filter(e -> e.kind() == Kind.ACTION_END && RAT.equals(e.resource())).findFirst().orElseThrow();
        assertThat(ratEnd.time()).isEqualTo(15.0);
        assertThat(ratEnd.cause()).isEqualTo(rat.seq());
        assertThat(ratEnd.root()).as("its start's root, from the earlier advance").isEqualTo(rat.seq());
        assertThat(store.state(RAT).actionInProgress().start()).isEqualTo(15.0);
    }

    @Test
    void everyEventAtTheStopTimeIsProcessed() {
        Store store = room(Room.rat(Room.repeatWait(1.0)));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        List<String> atTwelve = r.log().stream().filter(e -> e.time() == 12.0)
                .map(e -> e.kind().wire() + " " + e.resource()).toList();
        assertThat(atTwelve).containsExactly(
                "action-end " + ORBITER, "action-end " + RAT, "action-start " + RAT);
        assertThat(store.state(RAT).actionInProgress().end()).isEqualTo(13.0);
        assertThat(r.area().time()).isEqualTo(12.0);
    }

    @Test
    void eventsAtTheSameTimeResolveInOrderOfId() {
        // A rat whose id (…01) is below the orbiter's (…04): at 12 its end goes first, and its
        // next start follows at once, before the orbiter's end (docs/actions.md, "Events").
        String low = "entity:00000000-0000-4000-8000-000000000001";
        Store store = room(null).put(low, Room.rat(Room.repeatWait(1.0)));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        List<String> atTwelve = r.log().stream().filter(e -> e.time() == 12.0)
                .map(e -> e.kind().wire() + " " + e.resource()).toList();
        assertThat(atTwelve).containsExactly(
                "action-end " + low, "action-start " + low, "action-end " + ORBITER);
    }

    @Test
    void anActionThatStartedBeforeTheLogEndsWithoutACause() {
        Entity waiting = Room.rat(Room.repeatWait(1.0)).withAction(new ActionInProgress("wait",
                Room.object("{\"duration\":1.0}"), 9.5, 10.5, Timing.NONE, null, null));
        Store store = room(waiting);
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());

        LogEntry end = r.log().stream().filter(e -> e.kind() == Kind.ACTION_END && RAT.equals(e.resource()))
                .findFirst().orElseThrow();
        assertThat(end.cause()).isNull();
        assertThat(end.root()).isEqualTo(end.seq());
    }

    @Test
    void anActionWhoseStartWasPrunedIsItsOwnRoot() {
        Entity waiting = Room.rat(null).withAction(new ActionInProgress("wait",
                Room.object("{\"duration\":1.0}"), 9.5, 10.5, Timing.NONE, 40L, null));
        Store store = room(waiting).logFrom(100, Map.of());
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());

        LogEntry end = r.log().stream().filter(e -> e.kind() == Kind.ACTION_END && RAT.equals(e.resource()))
                .findFirst().orElseThrow();
        assertThat(end.cause()).isEqualTo(40L);
        assertThat(end.root()).isEqualTo(40L);
        assertThat(r.log().getFirst().seq()).isEqualTo(100);
    }

    @Test
    void aBehaviourWithAChoiceThatCannotStartStartsNothingAndDrawsNothing() {
        Store store = room(Room.rat("""
                {"kind":"random","choices":[{"weight":1.0,"actionSpec":%s},
                 {"weight":1.0,"actionSpec":{"type":"throw","params":{}}}]}""".formatted(Room.waitJson(1.0))));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());

        assertThat(r.blocked()).containsOnlyKeys(RAT);
        assertThat(r.blocked().get(RAT)).isEqualTo("throw: not in the entity's actions [wait, step]");
        assertThat(r.log()).noneMatch(e -> RAT.equals(e.resource()));
        assertThat(r.write(RAT)).isEmpty();
    }

    @Test
    void anIntentWhoseStartLeavesAnotherControlledEntityIdleStopsAtOnce() {
        Entity second = Room.state("""
                {"resourceKind":"entity","entityKind":"player","prototype":"p2","name":"p2","position":{"x":1.0,"y":1.0},"actions":["wait"],
                 "controller":{"kind":"user","sub":"00000000-0000-4000-8000-0000000000aa"}}""");
        Store store = room(Room.rat(Room.repeatWait(1.0)))
                .put("entity:00000000-0000-4000-8000-00000000000a", second);
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.area().time()).isEqualTo(10.0);
        assertThat(kinds(r)).containsExactly(Kind.INTENT, Kind.ACTION_START, Kind.ACTION_START);
        assertThat(store.state(ORBITER).actionInProgress().end()).isEqualTo(12.0);
    }
}
