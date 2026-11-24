package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.controller.Chooser.Pick;
import com.pessimisticit.scarcity.world.model.Draws;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChooserTest {

    private static final List<String> ALL = List.of("step", "wait", "throw");

    @Test
    void onlyTheEntitysActionsAreEligible() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7);
        assertThat(chooser.eligible(List.of("throw", "fly", "step"))).extracting(Choice::name)
                .containsExactly("step", "throw");
        for (int i = 0; i < 100; i++) {
            Pick pick = chooser.pick(List.of("throw", "fly", "step")).orElseThrow();
            assertThat(pick.names()).containsExactly("step", "throw");
            assertThat(pick.draw().weights()).containsExactly(2.0, 1.0);
        }
        assertThat(chooser.pick(List.of("fly"))).isEmpty();
        assertThat(chooser.pick(List.of())).isEmpty();
    }

    @Test
    void onlyFeasibleChoicesAreDrawnFrom() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7);
        Pick pick = chooser.pick(ALL, choice -> choice.name().equals("wait")).orElseThrow();
        assertThat(pick.names()).containsExactly("wait");
        assertThat(pick.draw().weights()).containsExactly(1.0);
        assertThat(pick.choice().name()).isEqualTo("wait");
        assertThat(chooser.pick(ALL, choice -> false)).isEmpty();
    }

    @Test
    void aChoiceInfeasibleOnceIsACandidateAgainNextTime() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7);
        assertThat(chooser.pick(ALL, choice -> !choice.name().equals("step")).orElseThrow().names())
                .containsExactly("wait", "throw");
        assertThat(chooser.pick(ALL).orElseThrow().names()).containsExactly("step", "wait", "throw");
    }

    @Test
    void picksFollowTheWeightsOverTenThousandSeededDraws() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7);
        Map<String, Integer> counts = new HashMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            Pick pick = chooser.pick(ALL).orElseThrow();
            assertThat(pick.names()).containsExactly("step", "wait", "throw");
            assertThat(pick.draw().weights()).containsExactly(2.0, 1.0, 1.0);
            assertThat(pick.draw().value()).isEqualTo(Draws.pick(pick.draw().weights(), pick.draw().u()));
            assertThat(pick.choice().name()).isEqualTo(pick.names().get(pick.draw().value()));
            counts.merge(pick.choice().name(), 1, Integer::sum);
        }
        // sd of a share near 0.5 over 10,000 draws is 0.005; 0.02 is four of them.
        assertThat(counts.get("step") / (double) n).isCloseTo(0.5, offset(0.02));
        assertThat(counts.get("wait") / (double) n).isCloseTo(0.25, offset(0.02));
        assertThat(counts.get("throw") / (double) n).isCloseTo(0.25, offset(0.02));
    }

    @Test
    void theSameSeedDrawsTheSameSequence() {
        Chooser a = Fixtures.chooser(Fixtures.WEIGHTS, 99);
        Chooser b = Fixtures.chooser(Fixtures.WEIGHTS, 99);
        for (int i = 0; i < 100; i++) {
            assertThat(a.pick(ALL).orElseThrow().draw()).isEqualTo(b.pick(ALL).orElseThrow().draw());
        }
    }

    @Test
    void aZeroWeightIsNeverChosen() {
        Chooser chooser = Fixtures.chooser(Map.of("wait", 1.0, "step", 0.0), 7);
        assertThat(chooser.eligible(ALL)).extracting(Choice::name).containsExactly("wait");
        for (int i = 0; i < 100; i++) {
            assertThat(chooser.pick(ALL).orElseThrow().names()).containsExactly("wait");
        }
    }

    @Test
    void anUnknownChoiceFailsAtStartup() {
        // A negative weight is Bean Validation's (ControllerPropertiesTest).
        assertThatThrownBy(() -> Fixtures.chooser(Map.of("dance", 1.0), 1))
                .hasMessageContaining("controller.weights: unknown choice 'dance'");
    }
}
