package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TurnsTest {

    private static final String OTHER = "entity:00000000-0000-4000-8000-00000000000b";

    /** Records each turn it plays and when, and ends it with the next scripted outcome (OK when none). */
    private static final class ScriptedPlayer extends Player {
        final List<TurnFrame> played = new CopyOnWriteArrayList<>();
        final List<Long> playedAt = new CopyOnWriteArrayList<>();
        final Queue<Outcome> script = new ConcurrentLinkedQueue<>();

        ScriptedPlayer() {
            super(null, null, new TestClock(), new SimpleMeterRegistry());
        }

        @Override
        public Outcome turn(TurnFrame turn) {
            playedAt.add(System.nanoTime());
            played.add(turn);
            Outcome next = script.poll();
            return next == null ? Outcome.OK : next;
        }
    }

    private final ScriptedPlayer player = new ScriptedPlayer();
    private Turns turns = turns(Duration.ZERO);

    private Turns turns(Duration think) {
        return new Turns(player, ObservationRegistry.NOOP, Fixtures.props("http://unused", "ws://unused", "k", think));
    }

    @AfterEach
    void stop() throws InterruptedException {
        turns.stop();
    }

    private static TurnFrame turn(String entity, long version) {
        return new TurnFrame(StubWorld.AREA, entity, version, 0.0);
    }

    private void settle() throws InterruptedException {
        Thread.sleep(150);
    }

    @Test
    void aTurnIsPlayedOnceHoweverOftenItIsRepeated() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            turns.offer(turn(StubWorld.ORBITER, 3));
        }
        settle();
        turns.offer(turn(StubWorld.ORBITER, 3));
        turns.offer(turn(StubWorld.ORBITER, 2)); // an older one, late
        settle();
        assertThat(player.played).containsExactly(turn(StubWorld.ORBITER, 3));

        turns.offer(turn(StubWorld.ORBITER, 4));
        turns.offer(turn(OTHER, 3));
        settle();
        assertThat(player.played).containsExactly(turn(StubWorld.ORBITER, 3), turn(StubWorld.ORBITER, 4),
                turn(OTHER, 3));
    }

    @Test
    void aTurnThatFailedIsPlayedAgainOnItsNextRepeat() throws InterruptedException {
        player.script.add(Outcome.UNREACHABLE);
        player.script.add(Outcome.INVALID);
        for (int i = 0; i < 3; i++) {
            turns.offer(turn(StubWorld.ORBITER, 3));
            settle();
        }
        turns.offer(turn(StubWorld.ORBITER, 3)); // the third ended OK
        settle();
        assertThat(player.played).hasSize(3).containsOnly(turn(StubWorld.ORBITER, 3));
    }

    @Test
    void aConflictOrStaleTurnIsNotPlayedAgain() throws InterruptedException {
        player.script.add(Outcome.CONFLICT);
        player.script.add(Outcome.STALE);
        turns.offer(turn(StubWorld.ORBITER, 3));
        settle();
        turns.offer(turn(StubWorld.ORBITER, 3));
        turns.offer(turn(OTHER, 1));
        settle();
        turns.offer(turn(OTHER, 1));
        settle();
        assertThat(player.played).containsExactly(turn(StubWorld.ORBITER, 3), turn(OTHER, 1));
    }

    @Test
    void theTurnIsPlayedAfterTheThinkingTime() throws InterruptedException {
        turns.stop();
        turns = turns(Duration.ofMillis(300));
        long offered = System.nanoTime();
        turns.offer(turn(StubWorld.ORBITER, 3));
        Thread.sleep(150);
        assertThat(player.played).isEmpty();
        Thread.sleep(400);
        assertThat(player.played).hasSize(1);
        assertThat(Duration.ofNanos(player.playedAt.getFirst() - offered)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }
}
