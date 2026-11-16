package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code GET /api/world/log}'s parameters (docs/log.md, "Reading the log"); the query is world-db's. */
class LogApiTest extends WorldTest {

    private static final String AREA = "area=" + WorldIds.AREA;

    private JsonNode ok(String query) throws Exception {
        HttpResponse<String> r = get(port, "/api/world/log" + query, TestSupport.validToken());
        assertThat(r.statusCode()).as(query + ": " + r.body()).isEqualTo(200);
        return json(r);
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.path("entries").forEach(e -> out.add(e.path("seq").asLong()));
        return out;
    }

    /** {@code n} orbiter waits: a snapshot, then intent, action-start, action-end each. */
    private void waits(int n) {
        for (int i = 0; i < n; i++) {
            intents.post(new Controller.Service("orbital"),
                    new IntentRequest(WorldIds.ORBITER, new ActionSpec("wait",
                            JSON.createObjectNode().put("duration", 1.0)), null, null));
        }
    }

    @Test
    void pagesAndFiltersByKind() throws Exception {
        waits(2);
        JsonNode all = ok("?" + AREA);
        assertThat(seqs(all)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(all.path("first").asLong()).isEqualTo(1);
        assertThat(all.path("last").asLong()).isEqualTo(7);
        assertThat(all.path("entries").get(0).path("area").asText()).isEqualTo(WorldIds.AREA);

        JsonNode p2 = ok("?" + AREA + "&after=2&limit=2");
        assertThat(seqs(p2)).containsExactly(3L, 4L);
        assertThat(p2.path("last").asLong()).isEqualTo(7); // the whole log, not the page
        assertThat(seqs(ok("?" + AREA + "&after=7"))).isEmpty();

        assertThat(seqs(ok("?" + AREA + "&kind=action-end"))).containsExactly(4L, 7L);
        assertThat(ok("?" + AREA + "&kind=nope").path("entries")).isEmpty();
    }

    @Test
    void limitIsClampedTo500() throws Exception {
        jdbc.update("INSERT INTO area_log (area_id, seq, game_time, wall_time, kind, root, body) "
                + "SELECT ?, s, 0.0, now(), 'intent', s, '{}'::jsonb FROM generate_series(1, 600) s", WorldIds.AREA);
        JsonNode page = ok("?" + AREA + "&limit=1000");
        assertThat(page.path("entries")).hasSize(500);
        assertThat(page.path("last").asLong()).isEqualTo(600);
    }

    @Test
    void badParametersAre400() throws Exception {
        String tok = TestSupport.validToken();
        for (String q : List.of("?" + AREA + "&limit=0", "?" + AREA + "&limit=x", "?" + AREA + "&after=-1",
                "?" + AREA + "&after=abc", "")) {
            assertThat(get(port, "/api/world/log" + q, tok).statusCode()).as(q).isEqualTo(400);
        }
    }

    @Test
    void anUnknownAreaHasAnEmptyLogAndATokenIsRequired() throws Exception {
        JsonNode empty = ok("?area=area:00000000-0000-4000-8000-0000000000ff");
        assertThat(empty.path("entries")).isEmpty();
        assertThat(empty.path("first").isNull()).isTrue();
        assertThat(empty.path("last").isNull()).isTrue();
        assertThat(get(port, "/api/world/log?" + AREA, null).statusCode()).isEqualTo(401);
    }
}
