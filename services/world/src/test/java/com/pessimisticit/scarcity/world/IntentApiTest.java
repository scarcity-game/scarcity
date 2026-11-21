package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** {@code POST /api/world/intents} (docs/actions.md, "Intents"). */
class IntentApiTest extends WorldTest {

    @Test
    void anIntentAdvancesTheAreaAndReturnsItsTimeSeqAndTheEntityVersion() throws Exception {
        insertHero();
        double ok = intentCount("ok");
        HttpResponse<String> r = postIntent(waitIntent(HERO, 1.0), TestSupport.validToken());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.path("area").asText()).isEqualTo(WorldIds.AREA);
        // The orbiter is controlled, idle and pending: it is up at once.
        assertThat(body.path("time").asDouble()).isEqualTo(0.0);
        assertThat(body.path("version").asLong()).isEqualTo(2).isEqualTo(resource(HERO).version());
        assertThat(entity(HERO).actionInProgress().type()).isEqualTo("wait");
        assertThat(intentCount("ok")).isEqualTo(ok + 1);
        assertThat(meters.find("world.advance.events").summary()).isNotNull();
    }

    @Test
    void anIntentPublishesOneBatchAndLogsIntentThenActionStart() throws Exception {
        insertHero();
        long logSeq = json(postIntent(waitIntent(HERO, 1.0), TestSupport.validToken())).path("logSeq").asLong();

        long deadline = System.currentTimeMillis() + 10_000;
        while (BATCHES.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(300); // a second batch would have arrived by now
        assertThat(BATCHES).hasSize(1);
        ChangeBatch batch = BATCHES.peek();
        assertThat(batch.area()).isEqualTo(WorldIds.AREA);
        assertThat(batch.logSeq()).isEqualTo(logSeq);
        // The chain starts at the empty log; the snapshot entry is inside this advance.
        assertThat(batch.fromLogSeq()).isZero();
        assertThat(batch.traceparent()).isNotBlank();

        JsonNode log = json(get(port, "/api/world/log?area=" + WorldIds.AREA, TestSupport.validToken()));
        List<String> kinds = new ArrayList<>();
        log.path("entries").forEach(e -> kinds.add(e.path("kind").asText()));
        // The first advance of an area writes a snapshot first (docs/log.md).
        assertThat(kinds).containsExactly("snapshot", "intent", "action-start");
        assertThat(log.path("last").asLong()).isEqualTo(logSeq);
        JsonNode intent = log.path("entries").get(1);
        assertThat(intent.path("resource").asText()).isEqualTo(HERO);
        assertThat(intent.path("controller").path("sub").asText()).isEqualTo(SUB);
    }

    @Test
    void eachRejectionHasItsStatusAndWritesNothing() throws Exception {
        insertHero();
        String tok = TestSupport.validToken();
        assertStatus(postIntent(waitIntent(HERO, 1.0), null), 401);
        assertStatus(postIntent("not json", tok), 400);
        assertStatus(postIntent("{\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}", tok), 400);
        assertStatus(postIntent("{\"entity\":\"" + HERO + "\"}", tok), 400);
        assertStatus(postIntent(waitIntent(WorldIds.ORBITER, 1.0), tok), 403);
        assertStatus(postIntent(waitIntent("entity:00000000-0000-4000-8000-0000000000ff", 1.0), tok), 404);
        assertStatus(postIntent(waitIntent(WorldIds.GAME, 1.0), tok), 404);
        assertStatus(postIntent(waitIntent(WorldIds.AREA, 1.0), tok), 404);
        assertStatus(postIntent("{\"entity\":\"" + HERO + "\",\"ifVersion\":7,"
                + "\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}", tok), 409);
        assertStatus(postIntent(intent(HERO, "dance", "{}"), tok), 422);
        assertStatus(postIntent(intent(HERO, "throw", "{\"at\":{\"x\":5.0,\"y\":4.0}}"), tok), 422);
        assertStatus(postIntent(waitIntent(HERO, -1.0), tok), 422);
        assertThat(resource(HERO).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM area_log", Long.class)).isZero();

        // The orbiter is pending while the hero is up (docs/actions.md, "Turns").
        HttpResponse<String> pending = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertStatus(pending, 409);
        assertThat(json(pending).path("error").asText()).contains("is pending");

        assertStatus(postIntent(waitIntent(HERO, 1.0), tok), 200);
        // The hero is busy; the orbiter is up now.
        assertThat(area().activeEntity().entity()).isEqualTo(WorldIds.ORBITER);
        assertThat(area().pending()).isEmpty();
        HttpResponse<String> busy = postIntent(waitIntent(HERO, 1.0), tok);
        assertStatus(busy, 409);
        assertThat(json(busy).path("error").asText()).isNotBlank();
    }

    @Test
    void twoConcurrentIntentsOnOneVersionGiveOne200AndOne409() throws Exception {
        insertHero();
        String body = "{\"entity\":\"" + HERO + "\",\"ifVersion\":1,"
                + "\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}";
        String tok = TestSupport.validToken();
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> post = () -> {
            go.await();
            return postIntent(body, tok).statusCode();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(post);
            Future<Integer> b = pool.submit(post);
            go.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aServicePrincipalMayPostOnlyForTheEntitiesItControls() throws Exception {
        assertStatus(postIntent(waitIntent(WorldIds.ORBITER, 1.0), STRANGER), 403);
        HttpResponse<String> orbital = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertStatus(orbital, 200);
        // The only controlled entity is idle again when its wait ends.
        assertThat(json(orbital).path("time").asDouble()).isEqualTo(1.0);
        assertThat(area().time()).isEqualTo(1.0);
    }

    @Test
    void nothingAdvancesWithoutIntents() throws Exception {
        long area = resource(WorldIds.AREA).version();
        long orbiter = resource(WorldIds.ORBITER).version();
        Thread.sleep(2_000);
        assertThat(resource(WorldIds.AREA).version()).isEqualTo(area);
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(orbiter);
        assertThat(BATCHES).isEmpty();
    }

    private static String intent(String entity, String type, String params) {
        return "{\"entity\":\"" + entity + "\",\"actionSpec\":{\"type\":\"" + type + "\",\"params\":" + params + "}}";
    }

    private static void assertStatus(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    }

    private double intentCount(String outcome) {
        var c = meters.find("world.intents").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }
}
