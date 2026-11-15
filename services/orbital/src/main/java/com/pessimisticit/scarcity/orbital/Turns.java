package com.pessimisticit.scarcity.orbital;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Acts on {@code turn} frames: once per entity and version, {@code orbital.think} after the
 * frame, on one thread, so turns never overlap. Each turn is one {@code orbital.turn}
 * observation, so its calls to world (and world's advance) share one trace.
 *
 * <p>subscriptions repeats a turn after every {@code changes} frame for the area and every
 * digest (about 30 s). A turn that ended in a way the same version can't change (posted,
 * 409, stale) ignores those repeats; one that failed (world down, a refused choice) plays
 * again on the next repeat. No timer of orbital's retries anything.
 */
@Component
public class Turns {

    private static final Logger LOG = LoggerFactory.getLogger(Turns.class);
    /** Outcomes after which a repeat of the same turn is ignored. */
    private static final Set<Outcome> SETTLED = EnumSet.of(Outcome.OK, Outcome.CONFLICT, Outcome.STALE);
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final Player player;
    private final ObservationRegistry observations;
    private final Duration think;
    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("orbital-turns").factory());
    /** entity → the version whose turn settled. */
    private final Map<String, Long> settled = new HashMap<>();
    /** The turns scheduled or being played, as entity@version. */
    private final Set<String> playing = new HashSet<>();

    public Turns(Player player, ObservationRegistry observations, OrbitalProperties props) {
        this.player = player;
        this.observations = observations;
        this.think = props.think();
    }

    /** A {@code turn} frame arrived. Returns at once; the turn plays {@code think} later. */
    public synchronized void offer(TurnFrame turn) {
        Long done = settled.get(turn.entity());
        if (done != null && done >= turn.version() || !playing.add(key(turn))) {
            return;
        }
        LOG.debug("{} is up at version {}", turn.entity(), turn.version());
        thread.schedule(() -> play(turn), think.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void play(TurnFrame turn) {
        Outcome outcome = observed(turn);
        synchronized (this) {
            playing.remove(key(turn));
            if (SETTLED.contains(outcome)) {
                settled.merge(turn.entity(), turn.version(), Math::max);
            }
        }
    }

    private Outcome observed(TurnFrame turn) {
        Observation observation = Observation.createNotStarted("orbital.turn", observations);
        return observation.observe(() -> {
            Outcome outcome = player.turn(turn);
            observation.lowCardinalityKeyValue("outcome", outcome.tag());
            return outcome;
        });
    }

    private static String key(TurnFrame turn) {
        return turn.entity() + "@" + turn.version();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        thread.shutdownNow();
        thread.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
