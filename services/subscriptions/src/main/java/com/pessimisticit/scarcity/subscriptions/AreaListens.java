package com.pessimisticit.scarcity.subscriptions;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;

/** Starts and stops an {@link Area}'s batches (its channel's LISTEN, #378) for {@link AreaRegistry}. */
@Component
class AreaListens {

    /** How long a first subscriber waits for its area's LISTEN (it waits through a reconnect of the connection). */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ChangeSubscriber changeSubscriber;

    AreaListens(ChangeSubscriber changeSubscriber) {
        this.changeSubscriber = changeSubscriber;
    }

    /**
     * Caller holds {@code a}'s load lock. Once per area: blocks until its batches are received; throws if that
     * takes over {@link #TIMEOUT} (the listen still counts, and {@link #release} undoes it).
     */
    void listen(Area a) {
        synchronized (a) {
            if (a.listened) {
                return;
            }
            a.listened = true;
        }
        String area = a.id;
        try {
            changeSubscriber.listen(area).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while listening on " + area, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not listen on " + area + " in " + TIMEOUT, e);
        }
    }

    /** Caller holds {@code a}'s monitor (it is retired). Undoes its {@link #listen}, if it made one. */
    void release(Area a) {
        if (a.listened) {
            a.listened = false;
            changeSubscriber.unlisten(a.id);
        }
    }
}
