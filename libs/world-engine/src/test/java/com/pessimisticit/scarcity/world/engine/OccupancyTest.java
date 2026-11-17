package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.engine.IntentRejected.Reason;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An area runs only while a player or npc is in it, or one is arriving (docs/actions.md, "Turns"; #190):
 * with only mobs left it gives no turns and its time stands still.
 */
class OccupancyTest {

    private static final String STAIRS = "entity:00000000-0000-4000-8000-0000000000a1";
    private static final String PLACEHOLDER = "entity:00000000-0000-4000-8000-0000000000b1";
    private static final Controller MICROBE = new Controller.Service("microbe");

    /** The rat, played by {@code microbe}; busy until {@code until} when that is not null. */
    private static Entity mouse(Double until) {
        Entity mouse = Room.rat(null).withController(MICROBE);
        return until == null ? mouse : mouse.withAction(new ActionInProgress("wait",
                Room.object("{\"duration\":30.0}"), until - 30.0, until, Timing.NONE, null, null));
    }

    @Test
    void anAreaWithOnlyMobsHasNoActiveEntityAndRefusesTheirIntents() {
        Area area = new Area("Test room", 10.0, 8.0, 10.0, 0, new Turn(RAT, MICROBE, 10.0), List.of());
        Store store = new Store(area.withId(Room.AREA).withVersion(5)).put(RAT, mouse(null));

        AdvanceResult r = store.refresh(1).orElseThrow();
        assertThat(r.area().activeEntity()).isNull();
        assertThat(r.area().pending()).isEmpty();

        assertThatThrownBy(() -> store.advance(Intent.of(RAT, Room.waitFor(1.0), MICROBE), new ScriptedRandom()))
                .isInstanceOfSatisfying(IntentRejected.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIVE);
                    assertThat(e.getMessage()).contains("no player, npc or arrival");
                });
        assertThat(store.area().time()).isEqualTo(10.0);
    }

    @Test
    void theAreaStopsWhenItsLastNpcLeaves() {
        Entity traveller = Room.orbiter().withActions(List.of("step", "wait", "traverse"));
        Store store = new Store(Room.area(10.0)).put(ORBITER, traveller)
                .put(STAIRS, new Stairs("stairs").withPosition(new Position(8.5, 4.0)))
                .put(RAT, mouse(40.0));

        AdvanceResult r = store.advance(Room.intent(
                new ActionSpec("traverse", Room.object("{\"stairs\":\"" + STAIRS + "\"}"))), new ScriptedRandom());

        assertThat(r.departures()).hasSize(1);
        assertThat(r.area().time()).as("it stops at the departure, not at the mouse's wait").isEqualTo(11.0);
        assertThat(store.state(RAT).actionInProgress()).as("the mouse's wait is paused").isNotNull();
        assertThat(r.area().activeEntity()).isNull();
    }

    @Test
    void anArrivalGivesTheMobsTurnsUntilItEnds() {
        Entity placeholder = Arrive.placeholder(Room.orbiter().withId(ORBITER), new Position(5.0, 4.0), 3.0);
        Store store = new Store(Room.area(0.0)).put(PLACEHOLDER, placeholder).put(RAT, mouse(null));

        AdvanceResult warm = store.warmUp(3.0, new ScriptedRandom()).orElseThrow();
        assertThat(warm.area().time()).as("the warm-up stops for the idle mouse").isEqualTo(0.0);
        assertThat(warm.area().activeEntity()).isEqualTo(new Turn(RAT, MICROBE, 0.0));
        assertThat(store.has(ORBITER)).isFalse();

        AdvanceResult turn = store.advance(Intent.of(RAT, Room.waitFor(2.0), MICROBE), new ScriptedRandom());
        assertThat(turn.area().time()).isEqualTo(2.0);
        assertThat(turn.area().activeEntity()).isEqualTo(new Turn(RAT, MICROBE, 2.0));

        AdvanceResult last = store.advance(Intent.of(RAT, Room.waitFor(2.0), MICROBE), new ScriptedRandom());
        assertThat(last.area().time()).as("the arrival ends at game time").isEqualTo(3.0);
        assertThat(store.has(ORBITER)).isTrue();
        assertThat(last.area().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 3.0));
    }
}
