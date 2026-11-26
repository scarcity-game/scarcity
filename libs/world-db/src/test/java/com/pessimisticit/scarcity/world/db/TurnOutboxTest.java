package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The turns owed to intelligence services (docs/actions.md, "Intelligence services"; #373). */
class TurnOutboxTest extends DbTest {

    private static final String MOUSE = "entity:00000000-0000-4000-8000-0000000000a1";
    private static final Controller MICROBE = new Controller.Service("microbe");

    private final AtomicInteger woken = new AtomicInteger();
    private final TurnOutbox outbox = new TurnOutbox(JDBC, "microbe"::equals, woken::incrementAndGet);

    @Test
    void anIntelligenceTurnIsWrittenAndOthersClearTheRow() {
        String area = newArea();
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 3.0)));
        assertThat(rows(area)).isEqualTo(1);
        assertThat(woken).hasValue(1);

        tx.executeWithoutResult(s -> outbox.offer(area, up(new Controller.Service("diver"), 4.0)));
        assertThat(rows(area)).isZero();
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 5.0)));
        tx.executeWithoutResult(s -> outbox.offer(area, new Area(area, 6.0)));
        assertThat(rows(area)).isZero();
    }

    @Test
    void aRolledBackAdvanceWritesNothingAndWakesNobody() {
        String area = newArea();
        tx.executeWithoutResult(s -> {
            outbox.offer(area, up(MICROBE, 3.0));
            s.setRollbackOnly();
        });
        assertThat(rows(area)).isZero();
        assertThat(woken).hasValue(0);
    }

    @Test
    void theSameTurnOfferedAgainKeepsItsAttempts() {
        String area = newArea();
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 3.0)));
        TurnOutbox.Due due = claimMine(area);
        outbox.retry(due, "boom", Duration.ZERO);
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 3.0)));
        assertThat(JDBC.queryForObject("SELECT attempts FROM turn_outbox WHERE area_id = ?", Integer.class, area))
                .isEqualTo(1);
    }

    @Test
    void aClaimedTurnIsLeasedAndFinishingItKeepsANewerOne() {
        String area = newArea();
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 3.0)));
        TurnOutbox.Due due = claimMine(area);
        assertThat(due.entity()).isEqualTo(MOUSE);
        assertThat(due.since()).isEqualTo(3.0);
        assertThat(due.principal()).isEqualTo("microbe");
        // Leased: nobody claims it again until the lease ends.
        assertThat(outbox.claim(1000, Duration.ofMinutes(1))).noneMatch(d -> d.areaId().equals(area));

        // The intent's advance wrote the mouse's next turn before the dispatcher finished this one.
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 4.0)));
        outbox.finish(due);
        assertThat(rows(area)).isEqualTo(1);
        assertThat(claimMine(area).since()).isEqualTo(4.0);
    }

    @Test
    void aRetryCountsTheAttemptAndWaits() {
        String area = newArea();
        tx.executeWithoutResult(s -> outbox.offer(area, up(MICROBE, 3.0)));
        TurnOutbox.Due due = claimMine(area);
        outbox.retry(due, "503 from microbe", Duration.ofMinutes(5));
        assertThat(outbox.claim(1000, Duration.ofMinutes(1))).noneMatch(d -> d.areaId().equals(area));
        outbox.retry(due, "503 from microbe", Duration.ZERO);
        TurnOutbox.Due again = claimMine(area);
        assertThat(again.attempts()).isEqualTo(2);
        assertThat(JDBC.queryForObject("SELECT last_error FROM turn_outbox WHERE area_id = ?", String.class, area))
                .isEqualTo("503 from microbe");
        assertThat(outbox.waiting()).containsKey("microbe");
    }

    @Test
    void twoDispatchersNeverClaimOneTurn() throws Exception {
        List<String> areas = List.of(newArea(), newArea(), newArea(), newArea());
        tx.executeWithoutResult(s -> areas.forEach(a -> outbox.offer(a, up(MICROBE, 1.0))));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> tx.executeWithoutResult(s -> {
            // Holds the rows it locked until the second dispatcher has claimed.
            JDBC.query("SELECT area_id FROM turn_outbox WHERE area_id IN (?, ?) FOR UPDATE", rs -> { },
                    areas.get(0), areas.get(1));
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        List<String> second = outbox.claim(1000, Duration.ofMinutes(1)).stream().map(TurnOutbox.Due::areaId)
                .filter(areas::contains).toList();
        release.countDown();
        first.join();
        assertThat(second).containsExactlyInAnyOrder(areas.get(2), areas.get(3));
    }

    private TurnOutbox.Due claimMine(String area) {
        return outbox.claim(1000, Duration.ofMinutes(1)).stream().filter(d -> d.areaId().equals(area))
                .findFirst().orElseThrow();
    }

    private static Area up(Controller controller, double since) {
        return new Area("test", since).withQueue(List.of(new Turn(MOUSE, controller, since)));
    }

    private static int rows(String area) {
        Integer n = JDBC.queryForObject("SELECT count(*) FROM turn_outbox WHERE area_id = ?", Integer.class, area);
        return n == null ? 0 : n;
    }

    private static String newArea() {
        return "area:" + UUID.randomUUID();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
