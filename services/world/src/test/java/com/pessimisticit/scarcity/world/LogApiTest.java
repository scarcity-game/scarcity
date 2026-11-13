package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class LogApiTest extends LogTestBase {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String query, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/log" + query));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String query) throws Exception {
        HttpResponse<String> r = get(query, TestSupport.validToken());
        assertThat(r.statusCode()).as(query).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> out = new java.util.ArrayList<>();
        page.path("entries").forEach(e -> out.add(e.path("seq").asLong()));
        return out;
    }

    private void ticks(int n) {
        Ticker t = ticker(new java.util.SplittableRandom(1));
        for (int i = 0; i < n; i++) {
            now.addAndGet(1000);
            t.tick();
        }
    }

    @Test
    void pagesFilterAndFirstLast() throws Exception {
        ticks(5);
        String area = "area=" + WorldIds.AREA;
        JsonNode all = ok("?" + area);
        List<Long> allSeqs = seqs(all);
        assertThat(allSeqs.size()).isGreaterThan(6);
        assertThat(all.path("first").asLong()).isEqualTo(1);
        assertThat(all.path("last").asLong()).isEqualTo(allSeqs.get(allSeqs.size() - 1));
        // The HTTP entry is the stored one.
        assertThat(all.path("entries").get(0)).isEqualTo(JSON.readTree(entries().get(0).toString())); // Long vs Int nodes
        assertThat(all.path("entries").get(0).path("area").asText()).isEqualTo(WorldIds.AREA);

        JsonNode p1 = ok("?" + area + "&limit=2");
        assertThat(seqs(p1)).containsExactly(1L, 2L);
        assertThat(p1.path("last").asLong()).isEqualTo(all.path("last").asLong()); // whole log, not the page
        JsonNode p2 = ok("?" + area + "&after=2&limit=2");
        assertThat(seqs(p2)).containsExactly(3L, 4L);
        assertThat(seqs(ok("?" + area + "&after=" + all.path("last").asLong()))).isEmpty();

        JsonNode starts = ok("?" + area + "&kind=action-start");
        assertThat(starts.path("entries")).isNotEmpty();
        assertThat(starts.path("entries")).allSatisfy(e -> assertThat(e.path("kind").asText()).isEqualTo("action-start"));
        assertThat(starts.path("first").asLong()).isEqualTo(1);
        assertThat(ok("?" + area + "&kind=nope").path("entries")).isEmpty();
    }

    @Test
    void limitIsClampedTo500() throws Exception {
        ticks(270); // two entries per tick
        JsonNode page = ok("?area=" + WorldIds.AREA + "&limit=1000");
        assertThat(page.path("entries")).hasSize(500);
        assertThat(page.path("last").asLong()).isGreaterThan(500);
    }

    @Test
    void badParametersAre400() throws Exception {
        String tok = TestSupport.validToken();
        String area = "area=" + WorldIds.AREA;
        assertThat(get("?" + area + "&limit=0", tok).statusCode()).isEqualTo(400);
        assertThat(get("?" + area + "&limit=x", tok).statusCode()).isEqualTo(400);
        assertThat(get("?" + area + "&after=-1", tok).statusCode()).isEqualTo(400);
        assertThat(get("?" + area + "&after=abc", tok).statusCode()).isEqualTo(400);
        assertThat(get("", tok).statusCode()).isEqualTo(400);
    }

    @Test
    void anUnknownAreaHasAnEmptyLogAndATokenIsRequired() throws Exception {
        JsonNode empty = ok("?area=area:00000000-0000-4000-8000-0000000000ff");
        assertThat(empty.path("entries")).isEmpty();
        assertThat(empty.path("first").isNull()).isTrue();
        assertThat(empty.path("last").isNull()).isTrue();
        assertThat(get("?area=" + WorldIds.AREA, null).statusCode()).isEqualTo(401);
        assertThat(get("?area=" + WorldIds.AREA, "not.a.jwt").statusCode()).isEqualTo(401);
    }
}
