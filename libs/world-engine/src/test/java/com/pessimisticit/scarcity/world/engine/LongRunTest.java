package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * 2,000 time units of the test room: a test player posts random intents
 * for the orbiter (step, wait, throw), a rat wanders at random. Every
 * advance keeps the {@link Invariants}; the whole run replays the same.
 */
class LongRunTest {

    private static final double UNTIL = 2000.0;

    private static Store run(long seed) {
        Store store = Room.walled(0.0)
                .put(PILLAR, Room.pillar())
                .put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat("""
                        {"kind":"random","choices":[{"weight":2.0,"actionSpec":%s},
                         {"weight":1.0,"actionSpec":{"type":"step","params":{"to":{"x":1.0,"y":1.0}}}},
                         {"weight":1.0,"actionSpec":{"type":"step","params":{"to":{"x":3.0,"y":6.0}}}}]}"""
                        .formatted(Room.waitJson(0.7))));
        SplittableRandom area = new SplittableRandom(seed);
        SplittableRandom player = new SplittableRandom(seed + 1);
        while (store.area().time() < UNTIL) {
            try {
                store.advance(Room.intent(choose(player, store.state(ORBITER).position())), area);
            } catch (IntentRejected e) {
                store.advance(Room.intent(Room.waitFor(0.5)), area);
            }
            check(store);
        }
        return store;
    }

    private static ActionSpec choose(SplittableRandom player, Position at) {
        double kind = player.nextDouble();
        if (kind < 0.4) {
            return Room.stepTo(player.nextDouble(0.5, 9.5), player.nextDouble(0.5, 7.5));
        }
        if (kind < 0.7) {
            return Room.waitFor(player.nextDouble(0.2, 2.0));
        }
        double x = player.nextDouble(0.5, 9.5);
        return Room.throwAt(x == at.x() ? x + 0.1 : x, player.nextDouble(0.5, 7.5), 0.2);
    }

    private static void check(Store store) {
        for (Entity e : store.entities().values()) {
            Position p = e.position();
            double r = Contacts.TOUCH;
            if (e.polygon() == null) {
                // Debris lands on a wall's face, give or take a rounding error.
                assertThat(p.x() >= -r && p.x() <= 10.0 + r && p.y() >= -r && p.y() <= 8.0 + r)
                        .as(e.id() + " at " + p + " is inside the walls").isTrue();
            }
        }
        assertThat(store.entities()).as("rubble decays, so the area stays bounded").hasSizeLessThan(44);
        assertThat(store.state(PILLAR).hp()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void aLongSeededRunKeepsEveryRuleAndReplaysTheSame() {
        Store store = run(42);

        List<LogEntry> log = store.log();
        for (int i = 0; i < log.size(); i++) {
            assertThat(log.get(i).seq()).isEqualTo(i + 1L);
        }
        assertThat(log).anyMatch(e -> e.loggedEffect() != null && e.loggedEffect().type().equals("break"));
        assertThat(log).anyMatch(e -> e.loggedEffect() != null && e.loggedEffect().type().equals("damage"));
        assertThat(log).anyMatch(e -> RAT.equals(e.resource()) && e.kind() == LogEntry.Kind.ACTION_START
                && e.actionInProgress().type().equals("step"));
        assertThat(store.area().time()).isGreaterThanOrEqualTo(UNTIL);

        Store again = run(42);
        assertThat(again.log()).isEqualTo(log);
        assertThat(again.entities()).isEqualTo(store.entities());
    }
}
