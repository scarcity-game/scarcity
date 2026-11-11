package com.pessimisticit.scarcity.resourcemodel;

import java.time.Duration;
import java.util.function.Consumer;

/** Receives committed batches published by any service on the same database. */
public interface ChangeSubscriber {
    void addListener(Consumer<ChangeBatch> listener);

    void start();

    void stop();

    /** Blocks until LISTEN is active (true) or the timeout elapses (false). Default: always ready. */
    default boolean awaitListening(Duration timeout) throws InterruptedException {
        return true;
    }
}
