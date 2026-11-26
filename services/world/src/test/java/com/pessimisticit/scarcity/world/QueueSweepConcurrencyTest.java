package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Two world pods starting together (#381): each area's queue is written once, under the area's lock. */
class QueueSweepConcurrencyTest extends WorldTest {

    @Autowired
    QueueSweep queueSweep;

    @Test
    void twoSweepsAtOnceWriteEachQueueOnce() {
        List<Area> emptied = store.list(ResourceKind.AREA, Map.of(), 0, ResourceStore.MAX_SIZE)
                .items().stream().map(Area.class::cast).map(a -> a.withQueue(List.of())).toList();
        tx.executeWithoutResult(s -> emptied.forEach(store::update));
        long versionBefore = area().version();

        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = List.of(1, 2).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(go);
                    return queueSweep.run();
                }))
                .toList();
        go.countDown();
        int written = runs.stream().map(CompletableFuture::join).mapToInt(Integer::intValue).sum();

        assertThat(written).as("each area's queue is written by exactly one sweep").isEqualTo(emptied.size());
        assertThat(area().version()).as("bumped once by the sweep that won").isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM area_log WHERE kind = 'queue'", Long.class))
                .isEqualTo((long) emptied.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT area_id FROM area_log WHERE kind = 'queue'"
                + " GROUP BY area_id HAVING count(*) > 1) d", Long.class)).isZero();
        // turn_outbox has the area as its primary key, so a duplicate row cannot exist; one per area at most.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_outbox", Long.class))
                .isLessThanOrEqualTo((long) emptied.size());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
