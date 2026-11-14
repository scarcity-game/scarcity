package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.AREA;
import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.engine.LogEntry.Kind;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The example in docs/actions.md ("Order at the same game time") and the
 * throw tree in docs/log.md, scripted draw by draw.
 */
class ThrowTreeTest {

    private static final double HIT = 10.0 + 2.5 / 6.0;

    /** Time 10: the orbiter idle, the rat in a wait that ends at 11 (its start is seq 15), the log at seq 20. */
    private static Store example() {
        return new Store(Room.area(10.0))
                .put(PILLAR, Room.pillar())
                .put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat(Room.repeatWait(1.0)).withAction(new ActionInProgress("wait",
                        Room.object("{\"duration\":1.0}"), 10.0, 11.0, Timing.NONE, 15L, null)))
                .logFrom(20, Map.of(15L, 15L));
    }

    /** {@code seq kind subject <- cause}; the subject is the resource or the effect type. */
    private static List<String> tree(List<LogEntry> log) {
        return log.stream().map(e -> e.seq() + " " + e.kind().wire() + " "
                + (e.effect() != null ? e.effect().type() : name(e.resource())) + " <- " + e.cause()).toList();
    }

    private static String name(String id) {
        return Map.of(ORBITER, "orbiter", RAT, "rat", PILLAR, "pillar").getOrDefault(id, "new");
    }

    @Test
    void aThrowThatHitsAndBreaksIsOneTreeAndTheRatStillActs() {
        Store store = example();
        ScriptedRandom rng = new ScriptedRandom().normal(0.0, 0.4).uniform(0.1);
        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)), rng);

        assertThat(tree(r.log())).containsExactly(
                "20 intent orbiter <- null",
                "21 action-start orbiter <- 20",
                "22 effect line-attack <- 21",
                "23 effect create <- 22",
                "24 action-start new <- 23",
                "25 action-end new <- 24",
                "26 effect damage <- 25",
                "27 effect break <- 25",
                "28 effect destroy <- 27",
                "29 effect create <- 27",
                "30 action-start new <- 29",
                "31 action-end rat <- 15",
                "32 action-start rat <- null",
                "33 action-end orbiter <- 21",
                "34 action-end rat <- 32",
                "35 action-start rat <- null");
        assertThat(r.log().subList(0, 11)).allMatch(e -> e.root() == 20);
        assertThat(r.log().get(11).root()).isEqualTo(15);
        assertThat(rng.exhausted()).isTrue();

        assertThat(r.log().get(5).time()).isCloseTo(HIT, within(1e-12));
        assertThat(r.log().get(2).draws()).containsExactly(
                new LoggedDraw("scatter", new Draw.Gaussian(0.0, 0.1, null, 0.0)));
        assertThat(r.log().get(6).draws()).containsExactly(new LoggedDraw("damage",
                new Draw.Gaussian(2.0, 0.5, new Draw.Truncate(0.5, 4.0), 2.2)));
        assertThat(r.log().get(7).draws()).containsExactly(
                new LoggedDraw("break", new Draw.Bernoulli(0.5, 0.1, true)));
        assertThat(r.area().time()).isEqualTo(12.0);
        assertThat(store.state(PILLAR).hp()).isCloseTo(97.8, within(1e-12));
    }

    @Test
    void thePebbleIsOnlyInTheLogAndTheRubbleIsCreated() {
        Store store = example();
        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.4).uniform(0.1));

        String pebble = r.log().get(3).changes().getFirst().id();
        String rubble = r.log().get(9).changes().getFirst().id();
        assertThat(r.log().get(3).changes().getFirst()).satisfies(c -> {
            assertThat(c.op()).isEqualTo(LogChange.Op.CREATED);
            assertThat(c.parent()).isEqualTo(AREA);
            assertThat(c.version()).as("created and destroyed in one advance").isNull();
        });
        assertThat(r.write(pebble)).isEmpty();
        assertThat(r.created()).containsExactly(rubble);
        assertThat(r.destroyed()).isEmpty();
        assertThat(r.writes()).extracting(Write::id).containsExactly(PILLAR, ORBITER, RAT, rubble);
        assertThat(store.state(rubble).action().end()).isCloseTo(HIT + 20.0, within(1e-12));
        assertThat(store.state(RAT).action().end()).as("pending for the next advance").isEqualTo(13.0);
    }

    @Test
    void theRubbleDecaysInALaterAdvanceUnderTheThrowsRoot() {
        Store store = example();
        store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)), new ScriptedRandom().normal(0.0, 0.4).uniform(0.1));
        String rubble = store.log().get(9).changes().getFirst().id();

        AdvanceResult r = store.advance(Room.intent(Room.waitFor(20.0)), new ScriptedRandom());
        LogEntry end = r.log().stream().filter(e -> e.kind() == Kind.ACTION_END && rubble.equals(e.resource()))
                .findFirst().orElseThrow();
        LogEntry destroy = r.log().get(r.log().indexOf(end) + 1);
        assertThat(end.cause()).isEqualTo(30L);
        assertThat(end.root()).isEqualTo(20L);
        assertThat(destroy.effect().type()).isEqualTo("destroy");
        assertThat(destroy.root()).isEqualTo(20L);
        assertThat(r.destroyed()).containsExactly(rubble);
        assertThat(store.has(rubble)).isFalse();
    }

    @Test
    void aMissFliesToTheWallAndAPebbleThatDoesNotBreakDecaysItself() {
        Store store = example();
        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(10.0).uniform(0.9));

        assertThat(r.log()).noneMatch(e -> e.effect() != null && e.effect().type().equals("damage"));
        assertThat(r.write(PILLAR)).isEmpty();
        String pebble = r.created().getFirst();
        assertThat(store.state(pebble).action().type()).isEqualTo("decay");
        assertThat(store.state(pebble).name()).isEqualTo("pebble");
        LogEntry decayStart = r.log().stream()
                .filter(e -> e.kind() == Kind.ACTION_START && pebble.equals(e.resource())
                        && e.action().type().equals("decay"))
                .findFirst().orElseThrow();
        assertThat(r.log().stream().filter(e -> e.seq() == decayStart.cause()).findFirst().orElseThrow()
                .effect().type()).isEqualTo("break");
    }
}
