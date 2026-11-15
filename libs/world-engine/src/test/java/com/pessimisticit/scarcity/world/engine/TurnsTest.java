package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Turn;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The area's {@code turns} after each advance (docs/actions.md, "Game time"; #148). */
class TurnsTest {

    private static final String P2 = "entity:00000000-0000-4000-8000-00000000000a";
    private static final Controller PLAYER = new Controller.User("00000000-0000-4000-8000-0000000000aa");

    private static EntityState player(String action) {
        String a = action == null ? "" : ",\"action\":" + action;
        return Room.state("""
                {"kind":"player","name":"p2","position":{"x":1.0,"y":1.0},"actions":["wait","decay"],
                 "controller":{"kind":"user","sub":"00000000-0000-4000-8000-0000000000aa"}%s}""".formatted(a));
    }

    private static Store room(List<Turn> turns) {
        AreaState area = new AreaState("Test room", 10.0, 8.0, 10.0, turns);
        return new Store(new Area(Room.AREA, 5, area)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter());
    }

    private static Intent p2(double duration) {
        return Intent.of(P2, Room.waitFor(duration), PLAYER);
    }

    @Test
    void oneControllerIsUpWhenItsActionEnds() {
        Store store = room(List.of(new Turn(ORBITER, Room.ORBITAL, 10.0)));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.area().turns()).containsExactly(new Turn(ORBITER, Room.ORBITAL, 12.0));
        assertThat(store.area().state().turns()).isEqualTo(r.area().turns());
    }

    @Test
    void twoControllersAreUpTogetherByTimeThenId() {
        Store store = room(List.of(new Turn(P2, PLAYER, 4.0), new Turn(ORBITER, Room.ORBITAL, 10.0)))
                .put(P2, player(null));

        // p2 is still idle: the advance stops at once, and p2 keeps its since.
        AdvanceResult first = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());
        assertThat(first.area().time()).isEqualTo(10.0);
        assertThat(first.area().turns()).containsExactly(new Turn(P2, PLAYER, 4.0));

        // Both waits end at 12: both are up, the orbiter (…04) before p2 (…0a).
        AdvanceResult second = store.advance(p2(2.0), new ScriptedRandom());
        assertThat(second.area().time()).isEqualTo(12.0);
        assertThat(second.area().turns()).containsExactly(
                new Turn(ORBITER, Room.ORBITAL, 12.0), new Turn(P2, PLAYER, 12.0));

        // The orbiter acts; p2 is up from 12 still, and first.
        AdvanceResult third = store.advance(Room.intent(Room.waitFor(1.0)), new ScriptedRandom());
        assertThat(third.area().turns()).containsExactly(new Turn(P2, PLAYER, 12.0));
    }

    @Test
    void aControllerDestroyedInTheAdvanceDropsOut() {
        // p2's decay ends at 11 and destroys it; the orbiter's wait runs on to 15.
        Store store = room(List.of(new Turn(ORBITER, Room.ORBITAL, 10.0))).put(P2, player("""
                {"type":"decay","params":{"duration":3.0},"start":8.0,"end":11.0,"effect":"end"}"""));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(5.0)), new ScriptedRandom());

        assertThat(r.destroyed()).containsExactly(P2);
        assertThat(r.area().time()).isEqualTo(15.0);
        assertThat(r.area().turns()).containsExactly(new Turn(ORBITER, Room.ORBITAL, 15.0));
    }

    @Test
    void anAreaWithNoTurnsYetListsTheIdleControllerFromNow() {
        Store store = room(List.of()).put(P2, player(null));
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(2.0)), new ScriptedRandom());

        assertThat(r.area().turns()).containsExactly(new Turn(P2, PLAYER, 10.0));
    }
}
