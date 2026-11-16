package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.engine.IntentRejected.Reason;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Turn;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Who is up and who is pending after each advance (docs/actions.md, "Turns"; #148). */
class TurnsTest {

    private static final String P2 = "entity:00000000-0000-4000-8000-00000000000a";
    private static final Controller PLAYER = new Controller.User("00000000-0000-4000-8000-0000000000aa");

    private static EntityState player(String action) {
        String a = action == null ? "" : ",\"actionInProgress\":" + action;
        return Room.state("""
                {"entityKind":"player","name":"p2","position":{"x":1.0,"y":1.0},"actions":["wait","decay"],
                 "controller":{"kind":"user","sub":"00000000-0000-4000-8000-0000000000aa"}%s}""".formatted(a));
    }

    private static Store room(Turn activeEntity, List<Turn> pending) {
        AreaState area = new AreaState("Test room", 10.0, 8.0, 10.0, 0, activeEntity, pending);
        return new Store(new Area(Room.AREA, 5, area)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter());
    }

    private static Intent p2(double duration) {
        return Intent.of(P2, Room.waitFor(duration), PLAYER);
    }

    @Test
    void oneControllerIsUpWhenItsActionEnds() {
        Store store = room(new Turn(ORBITER, Room.ORBITAL, 10.0), List.of());
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 12.0));
        assertThat(r.areaState().pending()).isEmpty();
        assertThat(store.area().areaState()).isEqualTo(r.areaState());
    }

    @Test
    void twoControllersIdleAtOnceTakeTurnsByIdAndThePendingOneCannotAct() {
        Store store = room(new Turn(ORBITER, Room.ORBITAL, 10.0), List.of()).put(P2, player(null));

        // p2 is idle too, but the orbiter (…04) is before p2 (…0a) at 10.0. The advance stops at
        // once: p2 is still idle, and up now.
        AdvanceResult first = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());
        assertThat(first.areaState().time()).isEqualTo(10.0);
        assertThat(first.areaState().activeEntity()).isEqualTo(new Turn(P2, PLAYER, 10.0));
        assertThat(first.areaState().pending()).isEmpty();

        // Both waits end at 12: the orbiter is up, p2 pending.
        AdvanceResult second = store.advance(p2(2.0), new ScriptedRandom());
        assertThat(second.areaState().time()).isEqualTo(12.0);
        assertThat(second.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 12.0));
        assertThat(second.areaState().pending()).containsExactly(new Turn(P2, PLAYER, 12.0));

        // p2 may not act while pending; nothing changes.
        assertThatThrownBy(() -> store.advance(p2(1.0), new ScriptedRandom()))
                .isInstanceOfSatisfying(IntentRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIVE));
        assertThat(store.area().areaState()).isEqualTo(second.areaState());

        // The orbiter acts; p2 is up from 12.
        AdvanceResult third = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());
        assertThat(third.areaState().time()).isEqualTo(12.0);
        assertThat(third.areaState().activeEntity()).isEqualTo(new Turn(P2, PLAYER, 12.0));
        assertThat(third.areaState().pending()).isEmpty();
    }

    @Test
    void anEarlierSinceIsUpBeforeALowerIdAndKeepsItsSince() {
        // p2 has waited since 4.0, the orbiter since 10.0: p2 goes first although its id is higher.
        Store store = room(new Turn(P2, PLAYER, 4.0), List.of(new Turn(ORBITER, Room.ORBITAL, 10.0)))
                .put(P2, player(null));
        AdvanceResult r = store.advance(p2(1.0), new ScriptedRandom());

        assertThat(r.areaState().time()).isEqualTo(10.0);
        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 10.0));
        assertThat(r.areaState().pending()).isEmpty();
    }

    @Test
    void aControllerDestroyedInTheAdvanceDropsOut() {
        // p2's decay ends at 11 and destroys it; the orbiter's wait runs on to 15.
        Store store = room(new Turn(ORBITER, Room.ORBITAL, 10.0), List.of()).put(P2, player("""
                {"type":"decay","params":{"duration":3.0},"start":8.0,"end":11.0,"timing":"end"}"""));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(5.0)), new ScriptedRandom());

        assertThat(r.destroyed()).containsExactly(P2);
        assertThat(r.areaState().time()).isEqualTo(15.0);
        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 15.0));
        assertThat(r.areaState().pending()).isEmpty();
    }

    @Test
    void anAreaWithNobodyRecordedQueuesIdleControllersFromNow() {
        Store store = room(null, List.of()).put(P2, player(null));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(P2, PLAYER, 10.0));
    }

    @Test
    void anAreaWithNoQueueGetsOneFromItsEntitiesWithoutTimePassing() {
        Store store = room(null, List.of()).put(P2, player(null));

        AdvanceResult r = store.refresh(7).orElseThrow();

        assertThat(r.areaState().time()).isEqualTo(10.0);
        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 10.0));
        assertThat(r.areaState().pending()).containsExactly(new Turn(P2, PLAYER, 10.0));
        assertThat(r.areaVersion()).isEqualTo(6);
        assertThat(r.writes()).isEmpty();
        assertThat(r.log()).singleElement().satisfies(e -> {
            assertThat(e.seq()).isEqualTo(7);
            assertThat(e.kind()).isEqualTo(LogEntry.Kind.QUEUE);
            assertThat(e.changes()).isEmpty();
        });
    }

    @Test
    void aRightQueueIsLeftAlone() {
        Store store = room(new Turn(ORBITER, Room.ORBITAL, 4.0), List.of());

        assertThat(store.refresh(7)).isEmpty();
    }

    @Test
    void aMissingEntryIsQueuedFromNowAndTheKnownOneKeepsItsSince() {
        Store store = room(new Turn(ORBITER, Room.ORBITAL, 4.0), List.of()).put(P2, player(null));

        AdvanceResult r = store.refresh(7).orElseThrow();

        assertThat(r.areaState().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 4.0));
        assertThat(r.areaState().pending()).containsExactly(new Turn(P2, PLAYER, 10.0));
    }
}
