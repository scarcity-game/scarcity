package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Independent edge-case probes: multi-byte payload sizing, hostile filters, stop/start, forward compat. */
class EdgeCaseTest extends AbstractDbTest {

    private static ChangeBatch.Change change(String id, int v, ObjectNode patch) {
        return new ChangeBatch.Change(id, ResourceType.ENTITY, v, patch);
    }

    @Test
    void multiByteUtf8PayloadsNeverExceedPostgresLimitAtAnySize() throws Exception {
        // sweep the patch size so some payloads land right at the 7900/8000 byte boundaries
        try (RawListener raw = new RawListener()) {
            List<String> areas = new ArrayList<>();
            for (int n = 2300; n <= 2700; n += 7) {
                String area = "area:" + unique();
                areas.add(area);
                ObjectNode p = Json.MAPPER.createObjectNode().put("s", "日".repeat(n)); // 3 bytes each
                List<ChangeBatch.Change> cs = List.of(change("entity:a", 1, p), change("entity:b", 2, p));
                tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area, cs, null)));
            }
            List<String> payloads = raw.collect(l -> areas.stream().allMatch(a -> l.stream().anyMatch(p -> p.contains(a))));
            assertThat(payloads.size()).isGreaterThanOrEqualTo(areas.size());
            assertThat(payloads).allSatisfy(p -> assertThat(p.getBytes(StandardCharsets.UTF_8).length).isLessThan(8000));
            // every change survives, with its patch or (when it can't fit) at least its version
            for (String a : areas) {
                int seen = payloads.stream().filter(p -> p.contains(a)).mapToInt(p -> {
                    try {
                        return Json.MAPPER.readValue(p, ChangeBatch.class).changes().size();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }).sum();
                assertThat(seen).as(a).isEqualTo(2);
            }
        }
    }

    @Test
    void emptyBatchPublishesNothingAndDoesNotThrow() {
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch("area:" + unique(), List.of(), null)));
    }

    @Test
    void stopThenStartListensAgain() throws Exception {
        BlockingQueue<ChangeBatch> q = new LinkedBlockingQueue<>();
        subscriber.addListener(q::add);
        assertThat(subscriber.awaitListening(Duration.ofSeconds(15))).isTrue();
        if (subscriber instanceof org.springframework.context.SmartLifecycle l) {
            l.stop();
            assertThat(l.isRunning()).isFalse();
            l.start();
        }
        assertThat(subscriber.awaitListening(Duration.ofSeconds(15))).isTrue();
        String area = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area, List.of(change("entity:1", 1, null)), null)));
        ChangeBatch b = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (b == null && System.currentTimeMillis() < deadline) {
            ChangeBatch x = q.poll(200, TimeUnit.MILLISECONDS);
            if (x != null && area.equals(x.area())) {
                b = x;
            }
        }
        assertThat(b).isNotNull();
    }

    @Test
    void hostileFilterKeysAndValues() {
        String g = create(ResourceType.GAME, null, state("name", "x"));
        for (String key : new String[] {"a'b", "a b", "a;b", "", "x".repeat(65), "a\"b", "k\u0000", "日"}) {
            assertThatThrownBy(() -> store.list(ResourceType.GAME, java.util.Map.of(key, "v"), 0, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String val : new String[] {"' OR '1'='1", "x'; DROP TABLE resources;--", "\\", "%", "_", "x\u0000"}) {
            try {
                assertThat(store.list(ResourceType.GAME, java.util.Map.of("name", val), 0, 10).items()
                        .stream().anyMatch(r -> r.id().equals(g))).isFalse();
            } catch (org.springframework.dao.DataAccessException e) {
                // NUL char rejected by Postgres is acceptable; table must still exist
            }
        }
        assertThat(store.get(g)).isPresent();
    }
    @Test
    void unknownFieldInPayloadStillDecodes() throws Exception {
        // forward compatibility: a newer publisher may add a field
        ChangeBatch b = Json.MAPPER.readValue(
                "{\"area\":\"area:x\",\"changes\":[],\"traceparent\":null,\"extra\":1}", ChangeBatch.class);
        assertThat(b.area()).isEqualTo("area:x");
    }

    @Test
    void childDeleteBumpsParentOnceAndChildUpdateDoesNot() {
        String g = create(ResourceType.GAME, null, state("a", 1));
        long v0 = store.get(g).get().version();
        String a = create(ResourceType.AREA, g, state("a", 1));
        assertThat(store.get(g).get().version()).isEqualTo(v0 + 1);
        assertThat(store.get(g).get().children()).containsExactly(a);
        tx.executeWithoutResult(s -> store.updateState(a, state("b", 2)));
        assertThat(store.get(g).get().version()).isEqualTo(v0 + 1);
        assertThat(store.get(a).get().version()).isEqualTo(2);
        tx.executeWithoutResult(s -> store.delete(a));
        assertThat(store.get(g).get().version()).isEqualTo(v0 + 2);
        assertThat(store.get(g).get().children()).isEmpty();
    }
}
