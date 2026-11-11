package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

class ChangeNotificationTest extends AbstractDbTest {

    private final BlockingQueue<ChangeBatch> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void listen() throws Exception {
        subscriber.addListener(received::add);
        assertThat(subscriber.awaitListening(Duration.ofSeconds(15))).isTrue();
    }

    private ChangeBatch.Change change(String id, int v, ObjectNode patch) {
        return new ChangeBatch.Change(id, ResourceType.ENTITY, v, patch);
    }

    private ChangeBatch awaitForArea(String area) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ChangeBatch b = received.poll(200, TimeUnit.MILLISECONDS);
            if (b != null && area.equals(b.area())) {
                return b;
            }
        }
        throw new AssertionError("no batch for " + area);
    }

    @Test
    void committedBatchArrivesIdentical() throws Exception {
        String area = "area:" + unique();
        String tp = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        ChangeBatch sent = new ChangeBatch(area, List.of(
                change("entity:1", 7, Json.MAPPER.createObjectNode().put("x", 1.5).put("y", -2.25)),
                change("entity:2", 3, null)), tp);
        tx.executeWithoutResult(s -> publisher.publish(sent));
        assertThat(awaitForArea(area)).isEqualTo(sent);
    }

    @Test
    void rolledBackBatchNeverArrives() throws Exception {
        String rolled = "area:" + unique();
        String committed = "area:" + unique();
        tx.executeWithoutResult(s -> {
            publisher.publish(new ChangeBatch(rolled, List.of(change("entity:1", 2, null)), null));
            s.setRollbackOnly();
        });
        tx.executeWithoutResult(s ->
                publisher.publish(new ChangeBatch(committed, List.of(change("entity:1", 2, null)), null)));
        // notifications of one connection are ordered, so once the later one is here the earlier would be too
        long deadline = System.currentTimeMillis() + 10_000;
        boolean got = false;
        while (!got && System.currentTimeMillis() < deadline) {
            ChangeBatch b = received.poll(200, TimeUnit.MILLISECONDS);
            assertThat(b == null || !rolled.equals(b.area())).isTrue();
            got = b != null && committed.equals(b.area());
        }
        assertThat(got).isTrue();
    }

    @Test
    void bigBatchIsSplitIntoSeveralNotificationsUnderTheLimit() throws Exception {
        String area = "area:" + unique();
        List<ChangeBatch.Change> changes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            changes.add(change("entity:" + i, i + 1, Json.MAPPER.createObjectNode().put("pad", "p".repeat(40))
                    .put("i", i)));
        }
        try (RawListener raw = new RawListener()) {
            tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area, changes, null)));
            List<String> payloads = raw.collect(l -> l.stream().filter(p -> p.contains(area))
                    .mapToInt(p -> countChanges(p)).sum() >= 300);
            assertThat(payloads.size()).isGreaterThan(1);
            assertThat(payloads).allSatisfy(p -> assertThat(p.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThan(8000));
        }
        List<ChangeBatch> parts = new ArrayList<>();
        int total = 0;
        while (total < 300) {
            ChangeBatch b = awaitForArea(area);
            parts.add(b);
            total += b.changes().size();
        }
        assertThat(parts.size()).isGreaterThan(1);
        List<ChangeBatch.Change> all = parts.stream().flatMap(b -> b.changes().stream()).toList();
        assertThat(all).isEqualTo(changes);
    }

    private static int countChanges(String payload) {
        try {
            return Json.MAPPER.readValue(payload, ChangeBatch.class).changes().size();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void singleOversizedChangeKeepsVersionButDropsPatch() throws Exception {
        String area = "area:" + unique();
        ObjectNode big = Json.MAPPER.createObjectNode().put("blob", "z".repeat(9000));
        tx.executeWithoutResult(s ->
                publisher.publish(new ChangeBatch(area, List.of(change("entity:big", 42, big)), null)));
        ChangeBatch b = awaitForArea(area);
        assertThat(b.changes()).hasSize(1);
        assertThat(b.changes().get(0).version()).isEqualTo(42);
        assertThat(b.changes().get(0).id()).isEqualTo("entity:big");
        assertThat(b.changes().get(0).patch()).isNull();
    }

    @Test
    void listenerExceptionDoesNotKillTheThread() throws Exception {
        subscriber.addListener(b -> {
            throw new IllegalStateException("boom");
        });
        String area = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area, List.of(change("entity:1", 1, null)), null)));
        awaitForArea(area);
        String area2 = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area2, List.of(change("entity:1", 2, null)), null)));
        assertThat(awaitForArea(area2).changes().get(0).version()).isEqualTo(2);
    }

    @Test
    void subscriberReconnectsAfterItsConnectionIsKilled() throws Exception {
        int killed = jdbc.queryForObject("""
                SELECT count(pg_terminate_backend(pid))::int FROM pg_stat_activity
                 WHERE pid <> pg_backend_pid() AND application_name = 'resource-changes-listener'
                """, Integer.class);
        assertThat(killed).isGreaterThanOrEqualTo(1);

        String area = "area:" + unique();
        long deadline = System.currentTimeMillis() + 20_000;
        ChangeBatch got = null;
        while (got == null && System.currentTimeMillis() < deadline) {
            tx.executeWithoutResult(s ->
                    publisher.publish(new ChangeBatch(area, List.of(change("entity:r", 9, null)), null)));
            ChangeBatch b = received.poll(500, TimeUnit.MILLISECONDS);
            if (b != null && area.equals(b.area())) {
                got = b;
            }
        }
        assertThat(got).as("batch after reconnect").isNotNull();
        assertThat(subscriber.awaitListening(Duration.ofSeconds(5))).isTrue();
    }
}
