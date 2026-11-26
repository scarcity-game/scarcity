package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * An advance goes on through the turns a {@link Decider} decides, and stops at one it doesn't take
 * or can't decide now (docs/actions.md, "Game time").
 */
class DeciderTest {

    private static final String PLACEHOLDER = "entity:00000000-0000-4000-8000-0000000000b1";
    private static final Controller MICROBE = new Controller.Service("microbe");

    /** Decides {@code microbe}'s turns with {@code answer}, recording each turn it was asked. */
    private static final class Scripted implements Decider {
        final List<Turn> asked = new ArrayList<>();
        final Function<Turn, Optional<Decision>> answer;

        Scripted(Function<Turn, Optional<Decision>> answer) {
            this.answer = answer;
        }

        @Override
        public boolean decides(Controller controller) {
            return MICROBE.equals(controller);
        }

        @Override
        public Optional<Decision> decide(AreaView areaView, Turn turn) {
            assertThat(areaView.peek(turn.entity()).idle()).as("asked only for an idle entity").isTrue();
            asked.add(turn);
            return answer.apply(turn);
        }
    }

    private static Optional<Decider.Decision> waitFor(double duration) {
        return Optional.of(new Decider.Decision(Room.waitFor(duration), null));
    }

    private static Entity mouse() {
        return Room.rat(null).withController(MICROBE);
    }

    /** At 10 the orbiter is up and the rat, idle since 10 too, pending: the orbiter (…04) is before the rat (…09) by id. */
    private static Store room() {
        Area area = new Area("Test room", 10.0, 0, new Turn(ORBITER, Room.ORBITAL, 10.0),
                List.of(new Turn(RAT, MICROBE, 10.0)));
        return new Store(area.withId(Room.AREA).withVersion(5)).put(ORBITER, Room.orbiter()).put(RAT, mouse());
    }

    @Test
    void theMouseActsAsOftenAsItIsUpBeforeTheOrbitersNextTurn() {
        Store store = room();
        Scripted decider = new Scripted(turn -> waitFor(1.0));

        AdvanceResult r = store.advance(Room.intent(Room.waitFor(3.0)), new ScriptedRandom(), decider);

        // The mouse is up at 10, 11 and 12; at 13 both are idle and the orbiter (…04) is before the
        // rat (…09): the advance stops there, the rat pending.
        assertThat(decider.asked).extracting(Turn::since).containsExactly(10.0, 11.0, 12.0);
        assertThat(r.area().time()).isEqualTo(13.0);
        assertThat(r.area().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 13.0));
        assertThat(r.area().pending()).containsExactly(new Turn(RAT, MICROBE, 13.0));
        // Every decided turn is logged as its controller's intent.
        assertThat(r.log().stream().filter(e -> e.kind() == LogEntry.Kind.INTENT)).hasSize(4);
    }

    @Test
    void aTurnItCannotDecideNowEndsTheAdvanceWithThatTurnUp() {
        Store store = room();
        Scripted decider = new Scripted(turn -> turn.since() < 11.0 ? waitFor(1.0) : Optional.empty());

        AdvanceResult r = store.advance(Room.intent(Room.waitFor(3.0)), new ScriptedRandom(), decider);

        assertThat(r.area().time()).isEqualTo(11.0);
        assertThat(r.area().activeEntity()).isEqualTo(new Turn(RAT, MICROBE, 11.0));
        assertThat(r.blocked()).isEmpty();
    }

    @Test
    void aDecisionTheRulesRejectEndsTheAdvanceAndSaysWhy() {
        Store store = room();
        Scripted decider = new Scripted(turn -> Optional.of(new Decider.Decision(
                new ActionSpec("fly", Room.object("{}")), null)));

        AdvanceResult r = store.advance(Room.intent(Room.waitFor(3.0)), new ScriptedRandom(), decider);

        assertThat(r.area().time()).isEqualTo(10.0);
        assertThat(r.area().activeEntity()).isEqualTo(new Turn(RAT, MICROBE, 10.0));
        assertThat(r.blocked()).containsKey(RAT);
        assertThat(store.state(RAT).idle()).isTrue();
    }

    @Test
    void aWarmUpWhoseMobsAreAllDecidedIsOneAdvance() {
        Entity placeholder = Arrive.placeholder(Room.orbiter().withId(ORBITER), new Position(5.0, 4.0), 3.0);
        Store store = new Store(Room.area(0.0)).put(PLACEHOLDER, placeholder).put(RAT, mouse());
        Scripted decider = new Scripted(turn -> waitFor(2.0));

        AdvanceResult warm = store.warmUp(3.0, new ScriptedRandom(), decider).orElseThrow();

        // The mouse waits from 0 and from 2; the arrival ends at 3, game time, and the orbiter is up.
        assertThat(decider.asked).extracting(Turn::since).containsExactly(0.0, 2.0);
        assertThat(warm.area().time()).isEqualTo(3.0);
        assertThat(store.has(ORBITER)).isTrue();
        assertThat(warm.area().activeEntity()).isEqualTo(new Turn(ORBITER, Room.ORBITAL, 3.0));
    }

    @Test
    void withoutADeciderTheAdvanceStopsAtTheMouseAsBefore() {
        Store store = room();
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(3.0)), new ScriptedRandom());
        assertThat(r.area().time()).isEqualTo(10.0);
        assertThat(r.area().activeEntity()).isEqualTo(new Turn(RAT, MICROBE, 10.0));
    }
}
