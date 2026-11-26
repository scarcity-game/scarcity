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
import com.pessimisticit.scarcity.world.model.Stash;
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
        return Room.walled(10.0)
                .put(PILLAR, Room.pillar())
                .put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat(Room.repeatWait(1.0)).withAction(new ActionInProgress("wait",
                        Room.object("{\"duration\":1.0}"), 10.0, 11.0, Timing.NONE, 15L, null)))
                .logFrom(20, Map.of(15L, 15L));
    }

    /** {@code seq kind subject <- cause}; the subject is the resource or the effect type. */
    private static List<String> tree(List<LogEntry> log) {
        return log.stream().map(e -> e.seq() + " " + e.kind().wire() + " "
                + (e.loggedEffect() != null ? e.loggedEffect().type() : name(e.resource())) + " <- " + e.cause()).toList();
    }

    private static String name(String id) {
        return Map.of(ORBITER, "orbiter", RAT, "rat", PILLAR, "pillar").getOrDefault(id, "new");
    }

    @Test
    void aThrowThatHitsAndBreaksIsOneTreeAndTheRatStillActs() {
        Store store = example();
        ScriptedRandom rng = new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1);
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
                "29 effect settle <- 27",
                "30 effect create-item <- 29",
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
                new LoggedDraw("scatter", new Draw.Gaussian(0.0, 0.1, null, 0.0)),
                new LoggedDraw("radius", varied(0.015)), new LoggedDraw("mass", varied(30.0)));
        assertThat(r.log().get(6).draws()).containsExactly(new LoggedDraw("damage",
                new Draw.Gaussian(2.0, 0.5, new Draw.Truncate(0.5, 4.0), 2.2)));
        assertThat(r.log().get(7).draws()).containsExactly(
                new LoggedDraw("break", new Draw.Bernoulli(0.5, 0.1, true)));
        assertThat(r.area().time()).isEqualTo(12.0);
        assertThat(store.state(PILLAR).hp()).isCloseTo(97.8, within(1e-12));
    }

    @Test
    void thePebbleIsOnlyInTheLogAndItsRubbleLiesInANewStash() {
        Store store = example();
        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1));

        String pebble = r.log().get(3).changes().getFirst().id();
        LogEntry settle = r.log().get(9);
        String stash = settle.changes().getFirst().id();
        assertThat(r.log().get(3).changes().getFirst()).satisfies(c -> {
            assertThat(c.op()).isEqualTo(LogChange.Op.CREATED);
            assertThat(c.parent()).isEqualTo(AREA);
            assertThat(c.version()).as("created and destroyed in one advance").isNull();
        });
        assertThat(settle.result().get("stash").asString()).isEqualTo(stash);
        assertThat(settle.result().get("newStash").asBoolean()).isTrue();
        LogChange made = r.log().get(10).changes().getFirst();
        assertThat(made.type()).isEqualTo("item");
        assertThat(made.parent()).isEqualTo(stash);
        assertThat(r.write(pebble)).isEmpty();
        assertThat(r.created()).containsExactly(stash);
        assertThat(r.destroyed()).isEmpty();
        assertThat(r.writes()).extracting(Write::id).containsExactly(PILLAR, ORBITER, RAT, stash, made.id());
        assertThat(r.write(stash).orElseThrow().version()).as("made, then its first item: one bump").isEqualTo(2L);
        assertThat(store.state(stash)).isInstanceOf(Stash.class);
        assertThat(store.state(stash).radius()).as("a 30 g pile: the smallest radius").isEqualTo(Stash.radiusFor(30.0));
        assertThat(store.state(stash).actionInProgress()).as("nothing decays").isNull();
        assertThat(store.itemsIn(stash).values()).singleElement().satisfies(item -> {
            assertThat(item.prototype()).isEqualTo("rubble");
            assertThat(item.mass()).as("the pebble's mass").isEqualTo(30.0);
        });
        assertThat(store.state(RAT).actionInProgress().end()).as("pending for the next advance").isEqualTo(13.0);
    }

    @Test
    void rubbleLandingInTheSameStashStacksOntoTheRubbleThere() {
        Store store = example();
        store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)), new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1));
        String stash = store.log().get(9).changes().getFirst().id();
        String rubble = store.itemsIn(stash).firstKey();

        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1));
        LogEntry settle = r.log().stream().filter(e -> e.loggedEffect() != null
                && e.loggedEffect().type().equals("settle")).findFirst().orElseThrow();
        assertThat(settle.result().get("newStash").asBoolean()).isFalse();
        LogEntry stack = r.log().get(r.log().indexOf(settle) + 1);
        assertThat(stack.loggedEffect().type()).isEqualTo("stack");
        assertThat(stack.result().get("massAfter").asDouble()).isEqualTo(60.0);
        assertThat(r.created()).isEmpty();
        assertThat(r.write(stash)).as("a 60 g pile is still the smallest: the stash is unchanged").isEmpty();
        assertThat(store.state(stash).radius()).isEqualTo(Stash.radiusFor(60.0)).isEqualTo(Stash.MIN_RADIUS);
        assertThat(r.log().stream().flatMap(e -> e.changes().stream())).noneMatch(c -> c.id().equals(stash));
        assertThat(r.write(rubble).orElseThrow().version()).isEqualTo(2L);
        assertThat(store.itemsIn(stash)).containsOnlyKeys(rubble);
        assertThat(store.itemsIn(stash).get(rubble).mass()).isEqualTo(60.0);
        assertThat(stack.root()).as("under the second throw").isEqualTo(r.log().getFirst().seq());
    }

    @Test
    void aMissFliesToTheWallAndAPebbleThatDoesNotBreakLiesThereAsAPebble() {
        Store store = example();
        AdvanceResult r = store.advance(Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(10.0, 0.0, 0.0).uniform(0.9));

        // The pebble hits a wall, which has no hp: the damage is skipped.
        assertThat(r.log()).filteredOn(e -> e.loggedEffect() != null && e.loggedEffect().type().equals("damage"))
                .singleElement().satisfies(e -> {
                    assertThat(Room.walls()).containsKey(e.loggedEffect().params().get("target").asString());
                    assertThat(e.result().get("skipped").asString()).isEqualTo("no hp");
                });
        assertThat(r.write(PILLAR)).isEmpty();
        String stash = r.created().getFirst();
        assertThat(store.state(stash)).isInstanceOf(Stash.class);
        assertThat(store.itemsIn(stash).values()).singleElement().satisfies(item -> {
            assertThat(item.prototype()).isEqualTo("pebble");
            assertThat(item.count()).isEqualTo(1.0);
            assertThat(item.mass()).isEqualTo(30.0);
        });
        assertThat(r.log()).noneMatch(e -> e.actionInProgress() != null && e.actionInProgress().type().equals("decay"));
    }

    private static Draw.Gaussian varied(double value) {
        return new Draw.Gaussian(value, 0.05 * value, new Draw.Truncate(Double.MIN_VALUE, 2 * value), value);
    }
}
