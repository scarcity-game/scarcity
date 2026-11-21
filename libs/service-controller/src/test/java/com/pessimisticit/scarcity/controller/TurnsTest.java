package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TurnsTest {

    private static final String OTHER = "entity:00000000-0000-4000-8000-00000000000b";

    /** Records each turn it plays and when, and ends it with the next scripted outcome (OK when none). */
    private static final class ScriptedPlayer extends Player {
        final List<TurnFrame> played;
        final List<Long> playedAt;
        final Queue<Outcome> script;

        ScriptedPlayer() {
            super(null, null, Fixtures.props("http://unused", "ws://unused", "k"), new TestClock(), new SimpleMeterRegistry());
            this.played = new CopyOnWriteArrayList<>();
            this.playedAt = new CopyOnWriteArrayList<>();
            this.script = new ConcurrentLinkedQueue<>();
        }

        @Override
        public Outcome turn(TurnFrame turn) {
            playedAt.add(System.nanoTime());
            played.add(turn);
            Outcome next = script.poll();
            return next == null ? Outcome.OK : next;
        }
    }

    private final ScriptedPlayer scriptedPlayer;
    private Turns turns;

    TurnsTest() {
        this.scriptedPlayer = new ScriptedPlayer();
        this.turns = turns(Duration.ZERO);
    }

    private Turns turns(Duration think) {
        return new Turns(scriptedPlayer, ObservationRegistry.NOOP, Fixtures.props("http://unused", "ws://unused", "k", think));
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
        settle();
        assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 3));

        turns.offer(turn(StubWorld.ORBITER, 4));
        turns.offer(turn(OTHER, 3));
        settle();
        assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 3), turn(StubWorld.ORBITER, 4),
                turn(OTHER, 3));
    }

    @Test
    void onlyAnExactRepeatIsIgnoredAndALowerVersionIsPlayedWithAWarning() throws InterruptedException {
        Logger logger = (Logger) LoggerFactory.getLogger(Turns.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            turns.offer(turn(StubWorld.ORBITER, 5));
            settle();
            turns.offer(turn(StubWorld.ORBITER, 5));
            settle();
            assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 5));
            assertThat(appender.list).isEmpty();

            turns.offer(turn(StubWorld.ORBITER, 1));
            settle();
            assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 5), turn(StubWorld.ORBITER, 1));
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains(StubWorld.ORBITER, "went down", "5", "1");

            turns.offer(turn(StubWorld.ORBITER, 6));
            settle();
            assertThat(scriptedPlayer.played).hasSize(3).endsWith(turn(StubWorld.ORBITER, 6));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aTurnThatFailedIsPlayedAgainOnItsNextRepeat() throws InterruptedException {
        scriptedPlayer.script.add(Outcome.UNREACHABLE);
        scriptedPlayer.script.add(Outcome.INVALID);
        for (int i = 0; i < 3; i++) {
            turns.offer(turn(StubWorld.ORBITER, 3));
            settle();
        }
        turns.offer(turn(StubWorld.ORBITER, 3)); // the third ended OK
        settle();
        assertThat(scriptedPlayer.played).hasSize(3).containsOnly(turn(StubWorld.ORBITER, 3));
    }

    @Test
    void aFallbackWaitSettlesTheTurn() throws InterruptedException {
        scriptedPlayer.script.add(Outcome.FALLBACK);
        for (int i = 0; i < 3; i++) {
            turns.offer(turn(StubWorld.ORBITER, 3));
            settle();
        }
        assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 3));
    }

    @Test
    void aConflictOrStaleTurnIsNotPlayedAgain() throws InterruptedException {
        scriptedPlayer.script.add(Outcome.CONFLICT);
        scriptedPlayer.script.add(Outcome.STALE);
        turns.offer(turn(StubWorld.ORBITER, 3));
        settle();
        turns.offer(turn(StubWorld.ORBITER, 3));
        turns.offer(turn(OTHER, 1));
        settle();
        turns.offer(turn(OTHER, 1));
        settle();
        assertThat(scriptedPlayer.played).containsExactly(turn(StubWorld.ORBITER, 3), turn(OTHER, 1));
    }

    @Test
    void theTurnIsPlayedAfterTheThinkingTime() throws InterruptedException {
        turns.stop();
        turns = turns(Duration.ofMillis(300));
        long offered = System.nanoTime();
        turns.offer(turn(StubWorld.ORBITER, 3));
        Thread.sleep(150);
        assertThat(scriptedPlayer.played).isEmpty();
        Thread.sleep(400);
        assertThat(scriptedPlayer.played).hasSize(1);
        assertThat(Duration.ofNanos(scriptedPlayer.playedAt.getFirst() - offered)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }
}
