package com.pessimisticit.scarcity.controller;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Acts on {@code turn} frames: once per entity and version in a row, {@code controller.think} after the
 * frame, on one thread, so turns never overlap. Each turn is one {@code <principal>.turn}
 * observation, so its calls to world (and world's advance) share one trace.
 *
 * <p>subscriptions repeats a turn after every {@code changes} frame for the area and every
 * checksum (about 30 s). A turn that ended in a way the same version can't change (posted,
 * 409, stale) ignores those repeats; one that failed (world down, a refused choice) plays
 * again on the next repeat. Per entity only the last version played is kept, and a frame is
 * ignored only if its version equals it; a different version is played, and a lower one (the
 * resource contract says versions only go up) first logs a warning. No timer of the controller's retries anything.
 */
@Component
public class Turns {

    private static final Logger LOG = LoggerFactory.getLogger(Turns.class);
    /** Outcomes after which a repeat of the same turn is ignored. */
    private static final Set<Outcome> TURN_OUTCOMES_THAT_INDICATE_NOT_TO_RETRY =
            EnumSet.of(Outcome.OK, Outcome.FALLBACK, Outcome.CONFLICT, Outcome.STALE);
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final Player player;
    private final ObservationRegistry observations;
    private final TurnDelay delay;
    private final String turnName;
    private final ScheduledExecutorService thread;
    /** entity → the last version played whose turn is not to be retried. */
    private final Map<String, Long> lastPlayed;
    /** The turns scheduled or being played, as entity@version. */
    private final Set<String> playing;

    @Autowired
    public Turns(Player player, ObservationRegistry observations, ControllerProperties controllerProperties,
            ObjectProvider<TurnDelay> turnDelays) {
        this(player, observations, controllerProperties,
                turnDelays.getIfAvailable(() -> turn -> controllerProperties.think()));
    }

    public Turns(Player player, ObservationRegistry observations, ControllerProperties controllerProperties) {
        this(player, observations, controllerProperties, turn -> controllerProperties.think());
    }

    private Turns(Player player, ObservationRegistry observations, ControllerProperties controllerProperties,
            TurnDelay turnDelay) {
        this.thread = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(controllerProperties.principal() + "-turns").factory());
        this.lastPlayed = new HashMap<>();
        this.playing = new HashSet<>();
        this.player = player;
        this.observations = observations;
        this.delay = turnDelay;
        this.turnName = controllerProperties.principal() + ".turn";
    }

    /** A {@code turn} frame arrived. Returns at once; the turn plays later ({@link TurnDelay}, default {@code controller.think}). */
    public synchronized void offer(TurnFrame turn) {
        Long last = lastPlayed.get(turn.entity());
        boolean alreadyDone = (last != null) && (last == turn.version());
        boolean alreadyPlaying = !playing.add(key(turn));
        if (alreadyDone || alreadyPlaying) {
            return;
        }
        if ((last != null) && (turn.version() < last)) {
            LOG.warn("The resource version of {} went down, from {} to {}; playing it anyway",
                    turn.entity(), last, turn.version());
        }
        LOG.debug("{} is up at version {}", turn.entity(), turn.version());
        thread.schedule(() -> play(turn), delay.before(turn).toNanos(), TimeUnit.NANOSECONDS);
    }

    private void play(TurnFrame turn) {
        Outcome outcome = observed(turn);
        synchronized (this) {
            playing.remove(key(turn));
            if (TURN_OUTCOMES_THAT_INDICATE_NOT_TO_RETRY.contains(outcome)) {
                lastPlayed.put(turn.entity(), turn.version());
            }
        }
    }

    private Outcome observed(TurnFrame turn) {
        Observation observation = Observation.createNotStarted(turnName, observations);
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
