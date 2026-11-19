package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What world's HTTP API sends, byte for byte ({@link Golden}), through Spring
 * MVC's converters and, for the log, through Postgres jsonb: one line per
 * request, {@code METHOD path -> status content-type body}. The wall time of
 * log entries is the only value masked. See {@link Golden} for a changed line.
 */
class HttpGoldenTest extends WorldTest {

    private static final String WALL_TIME = "\"wallTime\":\"[^\"]*\"";

    private final List<String> lines = new ArrayList<>();

    private void record(String request, HttpResponse<String> r) {
        String type = r.headers().firstValue("Content-Type").orElse("-");
        lines.add(request + " -> " + r.statusCode() + " " + type + " "
                + r.body().replaceAll(WALL_TIME, "\"wallTime\":\"<masked>\""));
    }

    private void get(String path) throws Exception {
        record("GET " + path, get(port, path, TestSupport.validToken()));
    }

    private void post(String body, String token) throws Exception {
        record("POST /api/world/intents " + body, postIntent(body, token));
    }

    @Test
    void resourcesIntentsTheLogAndTheirErrorsAreSentAsTheirGoldens() throws Exception {
        get("/api/world/resources?type=game");
        get("/api/world/resources/" + WorldIds.GAME + "/children?type=area");
        get("/api/world/resources/" + WorldIds.AREA + "/children");
        get("/api/world/resources/" + WorldIds.DIVE_AREA + "/children?size=2&page=1");
        get("/api/world/resources/" + WorldIds.ORBITER);
        get("/api/world/resources/entity:00000000-0000-4000-8000-0000000000ff");
        get("/api/world/resources?type=nope");

        post(waitIntent(WorldIds.ORBITER, 1.5), ORBITAL);
        post(waitIntent("entity:00000000-0000-4000-8000-0000000000ff", 1.0), ORBITAL);
        post(waitIntent(WorldIds.ORBITER, 1.0), STRANGER);
        post("{\"entity\":\"" + WorldIds.ORBITER + "\",\"actionSpec\":{\"type\":\"wait\",\"params\":{}},"
                + "\"ifVersion\":1.0}", ORBITAL);
        post("{\"entity\":", ORBITAL);

        get("/api/world/log?area=" + WorldIds.AREA);
        get("/api/world/log?area=" + WorldIds.AREA + "&after=1&limit=1&kind=intent");
        get("/api/world/log?area=" + WorldIds.AREA + "&limit=0");

        Golden.assertLines("http.txt", lines);
        assertThat(lines).hasSize(15);
    }
}
