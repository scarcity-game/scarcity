package com.pessimisticit.scarcity.resourcemodel;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Receives committed batches published by any service on the same database: the batches of the areas it
 * {@link #listen}s to, and every area's {@link TurnNotice}s (docs/resources.md, "Change notifications").
 */
public interface ChangeSubscriber {
    /** {@code listener} gets every batch of the areas listened to. */
    void addListener(Consumer<ChangeBatch> listener);

    /** {@code listener} gets every area's turn notices, whatever is listened to. */
    void addTurnListener(Consumer<TurnNotice> listener);

    /**
     * Starts receiving {@code areaId}'s batches; counted, so each call needs its {@link #unlisten}. The future
     * completes once {@code LISTEN} is in effect: a batch committed after that is received (load the area after
     * it, so nothing falls between). It never fails; while the connection is down it waits for the reconnect.
     */
    CompletableFuture<Void> listen(String areaId);

    /** Undoes one {@link #listen} of {@code areaId}; the last one stops its batches. */
    void unlisten(String areaId);

    void start();

    void stop();

    /**
     * True while the subscriber currently holds a working LISTEN connection and is receiving
     * changes. False before start, after stop/close, and while reconnecting.
     */
    boolean isListening();

    /** Blocks until LISTEN is active (true) or the timeout elapses (false). Default: always ready. */
    default boolean awaitListening(Duration timeout) throws InterruptedException {
        return true;
    }
}
