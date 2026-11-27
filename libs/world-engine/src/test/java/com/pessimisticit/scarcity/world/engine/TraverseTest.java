package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.AREA;
import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Placeholder;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import com.pessimisticit.scarcity.world.model.rules.Departure;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Crossing areas: {@code traverse}, the departure, and a placeholder's arrival (docs/actions.md). */
class TraverseTest {

    private static final String STAIRS = "entity:00000000-0000-4000-8000-0000000000a1";
    private static final String PAIR = "entity:00000000-0000-4000-8000-0000000000a2";
    private static final String PAIR_AREA = "area:00000000-0000-4000-8000-0000000000a2";

    private static ActionSpec traverse(String stairs) {
        return new ActionSpec("traverse", Room.object("{\"stairs\":\"" + stairs + "\"}"));
    }

    private static Store store(Stairs stairs, Position stairsAt) {
        Entity traveller = Room.orbiter().withActions(List.of("step", "wait", "traverse"));
        return new Store(Room.area(10.0)).put(ORBITER, traveller)
                .put(STAIRS, stairs.withPosition(stairsAt));
    }

    @Test
    void theFirstTraverseMakesTheDepartureAndLinksTheStairs() {
        Store store = store(Stairs.down("stairs"), new Position(8.5, 4.0));
        AdvanceResult r = store.advance(Room.intent(traverse(STAIRS)), new ScriptedRandom());

        assertThat(r.departures()).hasSize(1);
        Departure d = r.departures().getFirst();
        assertThat(d.entity().id()).isEqualTo(ORBITER);
        assertThat(d.entity().actionInProgress()).as("it leaves idle").isNull();
        assertThat(d.made()).isTrue();
        assertThat(d.fromStairs()).isEqualTo(STAIRS);
        assertThat(((Stairs) store.state(STAIRS)).to()).isEqualTo(d.toStairs());
        assertThat(((Stairs) store.state(STAIRS)).toArea()).as("the stairs name the level made").isEqualTo(d.toArea());
        assertThat(store.has(ORBITER)).as("it is out of the area").isFalse();
        assertThat(store.area().time()).as("a crossing takes 1.0").isEqualTo(11.0);
    }

    @Test
    void stairsThatAreLinkedMakeNoNewLevel() {
        Store store = store(Stairs.down("stairs").withTo(PAIR, PAIR_AREA), new Position(8.5, 4.0));
        AdvanceResult r = store.advance(Room.intent(traverse(STAIRS)), new ScriptedRandom());

        Departure d = r.departures().getFirst();
        assertThat(d.made()).isFalse();
        assertThat(d.toStairs()).isEqualTo(PAIR);
        assertThat(d.toArea()).as("a return names the area from the stairs").isEqualTo(PAIR_AREA);
    }

    @Test
    void stairsOutOfReachOrNotStairsAreRejected() {
        Store far = store(Stairs.down("stairs"), new Position(2.0, 1.0));
        assertThatThrownBy(() -> far.advance(Room.intent(traverse(STAIRS)), new ScriptedRandom()))
                .isInstanceOf(IntentRejected.class).hasMessageContaining("reach");
        Store notStairs = store(Stairs.down("stairs"), new Position(8.5, 4.0));
        assertThatThrownBy(() -> notStairs.advance(Room.intent(traverse(Room.PILLAR)), new ScriptedRandom()))
                .isInstanceOf(IntentRejected.class);
    }

    @Test
    void stairsUpThatLeadNowhereAreRejectedAndLinkedOnesAreTaken() {
        Store top = store(Stairs.up("stairs"), new Position(8.5, 4.0));
        assertThatThrownBy(() -> top.advance(Room.intent(traverse(STAIRS)), new ScriptedRandom()))
                .isInstanceOf(IntentRejected.class).hasMessageContaining("no level above");

        Store below = store(Stairs.up("stairs").withTo(PAIR, PAIR_AREA), new Position(8.5, 4.0));
        Departure d = below.advance(Room.intent(traverse(STAIRS)), new ScriptedRandom()).departures().getFirst();
        assertThat(d.made()).isFalse();
        assertThat(d.toStairs()).isEqualTo(PAIR);
    }

    private static Store arriving(double areaTime, double duration) {
        Entity traveller = Room.orbiter().withId(ORBITER);
        Entity placeholder = Arrive.placeholder(traveller, new Position(5.0, 4.0), duration);
        return new Store(Room.area(areaTime)).put("entity:00000000-0000-4000-8000-0000000000b1", placeholder);
    }

    @Test
    void aPlaceholderBecomesItsEntityWithTheSameIdAtGameTime() {
        Store store = arriving(0.0, 3.0);
        AdvanceResult r = store.warmUp(3.0, new ScriptedRandom()).orElseThrow();

        assertThat(store.has(ORBITER)).isTrue();
        assertThat(store.state(ORBITER).position()).isEqualTo(new Position(5.0, 4.0));
        assertThat(store.entities().values()).noneMatch(Placeholder.class::isInstance);
        assertThat(store.area().time()).isEqualTo(3.0);
        assertThat(r.log()).noneMatch(e -> e.kind() == LogEntry.Kind.CATCH_UP);
    }

    @Test
    void anAreaMoreThanTheWarmUpBehindFirstJumpsAndLogsIt() {
        Store store = arriving(0.0, Arrive.WARM_UP);
        AdvanceResult r = store.warmUp(1500.0, new ScriptedRandom()).orElseThrow();

        LogEntry jump = r.log().getFirst();
        assertThat(jump.kind()).isEqualTo(LogEntry.Kind.CATCH_UP);
        assertThat(jump.result().path("from").asDouble()).isEqualTo(0.0);
        assertThat(jump.result().path("to").asDouble()).isEqualTo(1500.0 - Arrive.WARM_UP);
        assertThat(store.area().time()).isEqualTo(1500.0);
        assertThat(store.has(ORBITER)).isTrue();
    }

    @Test
    void anAreaAlreadyAtGameTimeDoesNothing() {
        Store store = new Store(Room.area(7.0));
        assertThat(store.warmUp(7.0, new ScriptedRandom())).isEmpty();
        assertThat(AREA).isNotNull();
    }
}
