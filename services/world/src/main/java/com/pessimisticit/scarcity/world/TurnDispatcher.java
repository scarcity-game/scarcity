package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.db.TurnOutbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Dispatches the turn outbox to intelligence services (docs/actions.md, "Intelligence services";
 * #373): claims due turns whenever an advance writes one ({@link #wake}) and every {@code poll},
 * runs each on one of {@code workers} threads, and retries a failed one with exponential backoff and
 * jitter, forever. Does nothing when no service is registered.
 */
class TurnDispatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(TurnDispatcher.class);
    /** After the first failure of a turn, every this many. */
    private static final int WARN_EVERY = 10;

    private final TurnOutbox turnOutbox;
    private final TurnDispatch turnDispatch;
    private final IntelligenceProperties intelligenceProperties;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore free;
    private final AtomicBoolean polling;
    private final AtomicReference<Map<String, TurnOutbox.Waiting>> waiting;
    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private volatile boolean running;

    TurnDispatcher(TurnOutbox turnOutbox, TurnDispatch turnDispatch, IntelligenceProperties intelligenceProperties,
            Tracer tracer, MeterRegistry meters, Clock clock) {
        this.turnOutbox = turnOutbox;
        this.turnDispatch = turnDispatch;
        this.intelligenceProperties = intelligenceProperties;
        this.tracer = tracer;
        this.meters = meters;
        this.clock = clock;
        this.free = new Semaphore(intelligenceProperties.workers());
        this.polling = new AtomicBoolean();
        this.waiting = new AtomicReference<>(Map.of());
        for (String principal : intelligenceProperties.services().keySet()) {
            Gauge.builder("world.turns.waiting", waiting, w -> turns(w.get(), principal))
                    .description("turns owed to an intelligence service").tag("principal", principal)
                    .register(meters);
            Gauge.builder("world.turns.oldest", waiting, w -> oldestSeconds(w.get(), principal))
                    .description("seconds the oldest turn owed to an intelligence service has waited")
                    .baseUnit("seconds").tag("principal", principal).register(meters);
        }
    }

    /** An advance wrote a turn: look for due ones now, not at the next poll. */
    void wake() {
        if (running) {
            try {
                scheduler.execute(this::poll);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Stopping: nothing more is dispatched by this pod.
            }
        }
    }

    @Override
    public void start() {
        if (intelligenceProperties.services().isEmpty()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("turn-poll").factory());
        workers = Executors.newFixedThreadPool(intelligenceProperties.workers(),
                Thread.ofPlatform().name("turn-dispatch-", 0).factory());
        running = true;
        long every = intelligenceProperties.poll().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, 0, every, TimeUnit.MILLISECONDS);
        LOG.info("dispatching turns to {}", intelligenceProperties.services().keySet());
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            workers.shutdown();
            try {
                workers.awaitTermination(intelligenceProperties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Claims as many due turns as there are free workers, and hands each to one. */
    private void poll() {
        if (!running || !polling.compareAndSet(false, true)) {
            return;
        }
        int n = free.drainPermits();
        int handed = 0;
        try {
            List<TurnOutbox.Due> due = n == 0 ? List.of() : turnOutbox.claim(n, intelligenceProperties.lease());
            for (TurnOutbox.Due d : due) {
                workers.execute(() -> dispatch(d));
                handed++;
            }
            waiting.set(turnOutbox.waiting());
        } catch (RuntimeException e) {
            LOG.warn("turn poll failed: {}", e.toString());
        } finally {
            free.release(n - handed);
            polling.set(false);
        }
    }

    private void dispatch(TurnOutbox.Due due) {
        Span span = tracer.nextSpan().name("world.turn.dispatch").tag("scarcity.principal", due.principal())
                .tag("scarcity.entity", due.entity()).tag("scarcity.attempt", String.valueOf(due.attempts() + 1))
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            TurnDispatch.Outcome outcome = turnDispatch.run(due);
            span.tag("scarcity.outcome", outcome.tag());
            count(due, outcome.tag());
            if (outcome == TurnDispatch.Outcome.INVALID) {
                failed(due, "the answer was rejected as invalid");
            } else {
                turnOutbox.finish(due);
            }
        } catch (RuntimeException e) {
            span.error(e);
            count(due, "failed");
            failed(due, e.getMessage());
        } finally {
            span.end();
            free.release();
            wake();
        }
    }

    private void failed(TurnOutbox.Due due, String error) {
        Duration delay = delay(due.attempts());
        if (due.attempts() % WARN_EVERY == 0) {
            LOG.warn("{}'s turn of {} in {} failed (attempt {}), retrying in {}: {}", due.principal(), due.entity(),
                    due.areaId(), due.attempts() + 1, delay, error);
        }
        try {
            turnOutbox.retry(due, error, delay);
        } catch (RuntimeException e) {
            // The lease runs out and the turn is claimed again.
            LOG.warn("couldn't record the failed turn of {}: {}", due.entity(), e.toString());
        }
    }

    /** {@code backoff} doubled per earlier attempt, at most {@code backoff-max}, times a jitter in [0.5, 1). */
    private Duration delay(int attempts) {
        long base = intelligenceProperties.backoff().toMillis();
        long max = intelligenceProperties.backoffMax().toMillis();
        long doubled = attempts >= Long.SIZE - 2 ? max : Math.min(max, base << Math.min(attempts, 30));
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return Duration.ofMillis(Math.max(1, Math.round(doubled * jitter)));
    }

    private void count(TurnOutbox.Due due, String outcome) {
        Counter.builder("world.turns.dispatched").description("turns dispatched to intelligence services")
                .tag("principal", due.principal()).tag("outcome", outcome).register(meters).increment();
    }

    private static double turns(Map<String, TurnOutbox.Waiting> w, String principal) {
        TurnOutbox.Waiting of = w.get(principal);
        return of == null ? 0 : of.turns();
    }

    private double oldestSeconds(Map<String, TurnOutbox.Waiting> w, String principal) {
        TurnOutbox.Waiting of = w.get(principal);
        return of == null ? 0 : Duration.between(of.oldest(), clock.instant()).toMillis() / 1000.0;
    }
}
