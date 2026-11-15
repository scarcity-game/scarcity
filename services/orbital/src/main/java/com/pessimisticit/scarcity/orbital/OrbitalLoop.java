package com.pessimisticit.scarcity.orbital;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The one thread that plays: a turn, then the wait {@link Pacing} gives for its outcome
 * (counted from the response), then the next turn. Each turn is one {@code orbital.turn}
 * observation, so its calls to world (and world's advance) share one trace.
 */
public final class OrbitalLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OrbitalLoop.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final Player player;
    private final Pacing pacing;
    private final ObservationRegistry observations;
    private volatile boolean running;
    private Thread thread;

    public OrbitalLoop(Player player, Pacing pacing, ObservationRegistry observations) {
        this.player = player;
        this.pacing = pacing;
        this.observations = observations;
    }

    @Override
    public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().name("orbital-loop").start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(STOP_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        LOG.info("playing");
        while (running) {
            Duration wait = pacing.after(turn());
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("stopped");
    }

    private Outcome turn() {
        Observation turn = Observation.createNotStarted("orbital.turn", observations);
        return turn.observe(() -> {
            Outcome outcome = player.turn();
            turn.lowCardinalityKeyValue("outcome", outcome.tag());
            return outcome;
        });
    }
}
