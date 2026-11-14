package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@link AreaLock}: two advances of one area run one after the other. */
class LockTest extends DbTest {

    private final Room room = new Room(store, tx);

    @Test
    void aSecondBeginWaitsForTheFirstCommitAndContinuesItsSeq() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<AdvanceResult> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            AreaHead head = lock.begin(room.area, T0);
            firstHolds.countDown();
            await(release);
            AreaSnapshot snapshot = repo.load(room.area);
            AdvanceResult r = Engine.advance(snapshot, room.waitFor(1.0), new ScriptedRandom(), head.nextSeq(),
                    Catalogue.standard());
            repo.commit(head, snapshot, r, TRACEPARENT);
            return r;
        }));
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AdvanceResult> second = CompletableFuture.supplyAsync(
                () -> advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0));

        Thread.sleep(300);
        assertThat(second).isNotDone();
        release.countDown();
        AdvanceResult a = first.get(10, TimeUnit.SECONDS);
        AdvanceResult b = second.get(10, TimeUnit.SECONDS);

        assertThat(b.log().getFirst().seq()).isEqualTo(a.lastSeq() + 1);
        assertThat(b.areaVersion()).isEqualTo(a.areaVersion() + 1);
        assertThat(b.log().getFirst().time()).isEqualTo(a.area().time());
        List<Long> seqs = new ArrayList<>();
        for (JsonNode e : reader.read(room.area, 0, AreaLogReader.MAX_LIMIT, null).get("entries")) {
            seqs.add(e.get("seq").asLong());
        }
        assertThat(seqs).hasSize((int) b.lastSeq());
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
    }

    @Test
    void theFirstBeginCreatesTheHeadAndASnapshotIsDue() {
        AreaHead head = tx.execute(s -> lock.begin(room.area, T0));
        assertThat(head.lastSeq()).isZero();
        assertThat(head.lastSnapshotAt()).isNull();
        assertThat(head.snapshotDue()).isTrue();
        assertThat(head.snapshotSeq()).isEqualTo(1);
        assertThat(head.nextSeq()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
