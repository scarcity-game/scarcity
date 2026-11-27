package com.pessimisticit.scarcity.subscriptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The drain on shutdown (docs/websocket.md, "Drain"). Stops first (the highest phase, before the
 * web server's graceful shutdown): readiness goes down, so Envoy drops the pod from the hash ring;
 * after {@code drain-delay} the open sockets are closed with 1012, in random order spread over
 * {@code drain-window}, so their clients don't all reconnect at once. Envoy never moves an open
 * WebSocket itself. With no socket open it waits for nothing.
 */
@Component
class Drain implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Drain.class);

    private final SubscriptionsHandler subscriptionsHandler;
    private final SubscriptionsProperties subscriptionsProperties;
    private final ApplicationEventPublisher events;
    private volatile boolean running;

    Drain(SubscriptionsHandler subscriptionsHandler, SubscriptionsProperties subscriptionsProperties,
            ApplicationEventPublisher events) {
        this.subscriptionsHandler = subscriptionsHandler;
        this.subscriptionsProperties = subscriptionsProperties;
        this.events = events;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        if (subscriptionsHandler.clients().isEmpty()) {
            return;
        }
        sleep(subscriptionsProperties.getDrainDelay());
        List<Client> open = new ArrayList<>(subscriptionsHandler.clients());
        Collections.shuffle(open, ThreadLocalRandom.current());
        log.info("draining {} socket(s) over {}", open.size(), subscriptionsProperties.getDrainWindow());
        long gap = open.isEmpty() ? 0 : subscriptionsProperties.getDrainWindow().toNanos() / open.size();
        for (Client c : open) {
            c.close(Client.CLOSE_RESTART, "draining");
            sleep(Duration.ofNanos(gap));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops before everything else in the context, while the server and the LISTEN connection still run. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
