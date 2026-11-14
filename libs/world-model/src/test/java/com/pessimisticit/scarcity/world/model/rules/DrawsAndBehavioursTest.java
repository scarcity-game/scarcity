package com.pessimisticit.scarcity.world.model.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Behaviour;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Draws (the old BehavioursTest pick boundaries among them) and Behaviour.next. */
class DrawsAndBehavioursTest {

    private static final ActionSpec WAIT = new ActionSpec("wait", TestArea.object("{\"duration\":1.0}"));
    private static final ActionSpec STEP = new ActionSpec("step", TestArea.object("{\"to\":{\"x\":1.0,\"y\":1.0}}"));

    @Test
    void pickTwoEqualWeights() {
        assertThat(Draws.pick(List.of(1.0, 1.0), 0.0)).isEqualTo(0);
        assertThat(Draws.pick(List.of(1.0, 1.0), 0.4999999)).isEqualTo(0);
        assertThat(Draws.pick(List.of(1.0, 1.0), 0.5)).isEqualTo(1);
        assertThat(Draws.pick(List.of(1.0, 1.0), 0.9999999)).isEqualTo(1);
    }

    @Test
    void pickUnequalAndThreeWeights() {
        assertThat(Draws.pick(List.of(1.0, 3.0), 0.25)).isEqualTo(1);
        assertThat(Draws.pick(List.of(1.0, 3.0), 0.2499)).isEqualTo(0);
        assertThat(Draws.pick(List.of(5.0), 0.7)).isEqualTo(0);
        assertThat(Draws.pick(List.of(1.0, 2.0, 3.0), 0.34)).isEqualTo(1);
    }

    @Test
    void aTruncatedGaussianRecordsOnlyTheAcceptedValue() {
        // 2.0 + 0.5 * 10 = 7.0 and 2.0 - 5.0 = -3.0 are rejected, 2.0 + 0.5 * 1.0 = 2.5 is kept
        ScriptedRandom rng = new ScriptedRandom().normal(10.0, -10.0, 1.0);
        Draw.Gaussian d = Draws.truncatedGaussian(rng, 2.0, 0.5, 0.5, 4.0);
        assertThat(d).isEqualTo(new Draw.Gaussian(2.0, 0.5, new Draw.Truncate(0.5, 4.0), 2.5));
    }

    @Test
    void aTruncatedGaussianIncludesMinAndExcludesMax() {
        assertThat(Draws.truncatedGaussian(new ScriptedRandom().normal(-3.0), 2.0, 0.5, 0.5, 4.0).value())
                .isEqualTo(0.5);
        assertThat(Draws.truncatedGaussian(new ScriptedRandom().normal(4.0, 0.0), 2.0, 0.5, 0.5, 4.0).value())
                .isEqualTo(2.0);
    }

    @Test
    void gaussianAndBernoulliRecordWhatTheyDrew() {
        assertThat(Draws.gaussian(new ScriptedRandom().normal(1.5), 0.0, 0.2))
                .isEqualTo(new Draw.Gaussian(0.0, 0.2, null, 0.30000000000000004));
        assertThat(Draws.bernoulli(new ScriptedRandom().uniform(0.5), 0.5)).isEqualTo(new Draw.Bernoulli(0.5, 0.5, false));
        assertThat(Draws.bernoulli(new ScriptedRandom().uniform(0.49), 0.5).value()).isTrue();
        assertThatThrownBy(() -> Draws.bernoulli(new ScriptedRandom(), 1.5)).hasMessageStartingWith("p: ");
    }

    @Test
    void aRepeatPicksItsActionWithNoDraw() {
        Behaviour.Pick pick = new Behaviour.Repeat(WAIT).next(new ScriptedRandom());
        assertThat(pick).isEqualTo(new Behaviour.Pick(WAIT, null));
    }

    @Test
    void aRandomBehaviourPicksByWeightAndRecordsTheDraw() {
        Behaviour random = WorldJson.fromJson(TestArea.tree("""
                {"kind":"random","choices":[{"weight":2.0,"action":{"type":"wait","params":{"duration":1.0}}},
                 {"weight":1.0,"action":{"type":"step","params":{"to":{"x":1.0,"y":1.0}}}}]}"""), Behaviour.class);
        Behaviour.Pick pick = random.next(new ScriptedRandom().uniform(0.7));
        assertThat(pick.action()).isEqualTo(STEP);
        assertThat(pick.draw()).isEqualTo(new Draw.Categorical(List.of(2.0, 1.0), 0.7, 1));
    }

    @Test
    void aRandomBehaviourThatCannotPickIsInvalid() {
        Behaviour empty = new Behaviour.Random(List.of());
        assertThatThrownBy(() -> empty.next(new ScriptedRandom().uniform(0.1)))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("random behaviour: weights: at least one is needed");
        Behaviour zero = new Behaviour.Random(List.of(new Behaviour.Choice(0.0, WAIT)));
        assertThatThrownBy(() -> zero.next(new ScriptedRandom().uniform(0.1)))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("random behaviour: weights: must be > 0, got 0.0");
    }
}
