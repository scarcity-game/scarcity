package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.orbital.Chooser.Pick;
import com.pessimisticit.scarcity.world.model.Draws;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChooserTest {

    private final TestClock clock = new TestClock();

    @Test
    void picksFollowTheWeightsOverTenThousandSeededDraws() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7, clock);
        Map<String, Integer> counts = new HashMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            Pick pick = chooser.pick().orElseThrow();
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
        Chooser a = Fixtures.chooser(Fixtures.WEIGHTS, 99, clock);
        Chooser b = Fixtures.chooser(Fixtures.WEIGHTS, 99, clock);
        for (int i = 0; i < 100; i++) {
            assertThat(a.pick().orElseThrow().draw()).isEqualTo(b.pick().orElseThrow().draw());
        }
    }

    @Test
    void aSkippedChoiceSitsOutAMinute() {
        Chooser chooser = Fixtures.chooser(Fixtures.WEIGHTS, 7, clock);
        chooser.skip(firstNamed(chooser, "step"));
        for (int i = 0; i < 1000; i++) {
            Pick pick = chooser.pick().orElseThrow();
            assertThat(pick.names()).containsExactly("wait", "throw");
            assertThat(pick.draw().weights()).containsExactly(1.0, 1.0);
        }
        clock.advance(Duration.ofSeconds(59));
        assertThat(chooser.pick().orElseThrow().names()).doesNotContain("step");
        clock.advance(Duration.ofSeconds(1));
        assertThat(chooser.pick().orElseThrow().names()).containsExactly("step", "wait", "throw");
    }

    @Test
    void aZeroWeightIsNeverChosenAndNoneOpenIsEmpty() {
        Chooser chooser = Fixtures.chooser(Map.of("wait", 1.0, "step", 0.0), 7, clock);
        for (int i = 0; i < 100; i++) {
            assertThat(chooser.pick().orElseThrow().names()).containsExactly("wait");
        }
        chooser.skip(firstNamed(chooser, "wait"));
        assertThat(chooser.pick()).isEmpty();
    }

    @Test
    void badWeightsFailAtStartup() {
        assertThatThrownBy(() -> Fixtures.chooser(Map.of("dance", 1.0), 1, clock))
                .hasMessageContaining("orbital.weights: unknown choice 'dance'");
        assertThatThrownBy(() -> Fixtures.chooser(Map.of("step", -1.0), 1, clock))
                .hasMessage("orbital.weights.step: must be >= 0, got -1.0");
        assertThatThrownBy(() -> Fixtures.chooser(Map.of("step", Double.NaN), 1, clock))
                .hasMessageContaining("must be >= 0");
    }

    private static Choice firstNamed(Chooser chooser, String name) {
        for (int i = 0; i < 1000; i++) {
            Pick pick = chooser.pick().orElseThrow();
            if (pick.choice().name().equals(name)) {
                return pick.choice();
            }
        }
        throw new AssertionError("never drew " + name);
    }
}
