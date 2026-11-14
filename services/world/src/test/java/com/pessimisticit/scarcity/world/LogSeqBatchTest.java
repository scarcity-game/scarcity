package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;

class LogSeqBatchTest extends LogTestBase {

    private List<ChangeBatch> published = new ArrayList<>();

    private Ticker capturing(java.util.random.RandomGenerator g) {
        return ticker(g, batch -> {
            published.add(batch);
            publisher.publish(batch);
        });
    }

    private long lastSeq() {
        return areaLog.read(WorldIds.AREA, 0, 1, null).path("last").asLong();
    }

    @Test
    void aPlainActionTickPublishesLogSeq() throws Exception {
        setOrbiter(REPEAT_ORBIT, null);
        Ticker t = capturing(new SplittableRandom(1));
        t.tick();
        published.clear();
        now.addAndGet(1000);
        t.tick();

        assertThat(published).hasSize(1);
        assertThat(published.get(0).logSeq()).isNotNull().isEqualTo(lastSeq());

        // also assert the last log entry has seq equal to that logSeq
        JsonNode lastEntry = entries().get(entries().size() - 1);
        assertThat(lastEntry.path("seq").asLong()).isEqualTo(published.get(0).logSeq());
    }

    @Test
    void aThrowChainTickPublishesOneBatchWithTheLastSeq() throws Exception {
        // Start: ThrowScript g = new ThrowScript().uniform(0.9, 0.1).normal(0.0, 1.0);
        ThrowScript g = new ThrowScript().uniform(0.9, 0.1).normal(0.0, 1.0);
        Ticker t = capturing(g);
        t.tick();

        assertThat(published).hasSize(1);
        assertThat(entriesAfter(0).size()).isGreaterThan(3);

        // the batch's logSeq() equals lastSeq() and equals the seq of the last element of entries()
        assertThat(published.get(0).logSeq()).isEqualTo(lastSeq());
        JsonNode lastEntry = entriesAfter(0).get(entriesAfter(0).size() - 1);
        assertThat(lastEntry.path("seq").asLong()).isEqualTo(published.get(0).logSeq());
    }

    @Test
    void twoConsecutiveTicksGiveIncreasingLogSeq() throws Exception {
        setOrbiter(REPEAT_ORBIT, null);
        Ticker t = capturing(new SplittableRandom(1));
        t.tick();
        now.addAndGet(1000);
        t.tick();
        now.addAndGet(1000);
        t.tick();

        assertThat(published).hasSize(3); // if this is wrong for the first tick, use hasSizeGreaterThanOrEqualTo(2)
        // logSeq values are strictly increasing and the last equals lastSeq()
        assertThat(published.get(0).logSeq()).isLessThan(published.get(1).logSeq());
        assertThat(published.get(1).logSeq()).isLessThan(published.get(2).logSeq());
        assertThat(published.get(2).logSeq()).isEqualTo(lastSeq());
    }
}
