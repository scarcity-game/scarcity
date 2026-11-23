package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The resource API over the seeded test area, JWT enforcement, and actuator. */
class ResourceApiTest extends WorldTest {

    @Test
    void theApiRequiresAValidJwt() throws Exception {
        assertThat(get(port, "/api/world/resources?type=game", null).statusCode()).isEqualTo(401);
        HttpResponse<String> r = get(port, "/api/world/resources?type=game", "not.a.jwt");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEmpty();
        // Signed by a key the JWKS doesn't have, wrong issuer, expired.
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.OTHER_KEY, "scarcity-auth", 60_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.KEY, "someone-else", 60_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources?type=game",
                TestSupport.token(TestSupport.KEY, "scarcity-auth", -120_000)).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources/" + WorldIds.ORBITER, null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/anything/else", null).statusCode()).isEqualTo(401);
    }

    @Test
    void theSeededTestAreaIsServedWithETags() throws Exception {
        String tok = TestSupport.validToken();
        JsonNode games = json(get(port, "/api/world/resources?type=game", tok));
        assertThat(games.path("items")).hasSize(2);
        assertThat(games.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.GAME);
        assertThat(games.path("items").get(1).path("id").asText()).isEqualTo(WorldIds.DIVE_GAME);

        JsonNode areas = json(get(port, "/api/world/resources/" + WorldIds.GAME + "/children?type=area", tok));
        assertThat(areas.path("items")).hasSize(1);
        assertThat(areas.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.AREA);

        JsonNode ents = json(get(port, "/api/world/resources/" + WorldIds.AREA + "/children", tok));
        assertThat(ents.path("items")).hasSize(6);
        assertThat(ents.path("items").get(0).path("id").asText()).isEqualTo(WorldIds.PILLAR);
        assertThat(ents.path("items").get(1).path("id").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(ents.path("items").get(2).path("prototype").asText()).isEqualTo("stone-wall");
        assertThat(ents.path("items").get(2).path("polygon")).hasSize(4);

        HttpResponse<String> one = get(port, "/api/world/resources/" + WorldIds.ORBITER, tok);
        assertThat(one.statusCode()).isEqualTo(200);
        String etag = one.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + json(one).path("version").asLong() + "\"");
        assertThat(get(port, "/api/world/resources/" + WorldIds.ORBITER, tok, "If-None-Match", etag).statusCode())
                .isEqualTo(304);
        assertThat(get(port, "/api/world/resources/entity:00000000-0000-4000-8000-0000000000ff", tok)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void actuatorIsOpenOnTheManagementPortOnly() throws Exception {
        assertThat(mgmtPort).isNotEqualTo(port);
        assertThat(get(mgmtPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get(mgmtPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        HttpResponse<String> p = get(mgmtPort, "/actuator/prometheus", null);
        assertThat(p.statusCode()).isEqualTo(200);
        assertThat(p.body()).contains("jvm_memory");
        assertThat(get(port, "/actuator/health", null).statusCode()).isIn(401, 404);
    }

    @Test
    void versionsGiveTheAreaAndItsEntitiesWithTheirChecksumAndTheLogHead() throws Exception {
        String tok = TestSupport.validToken();
        JsonNode v = json(get(port, "/api/world/resources/" + WorldIds.AREA + "/versions", tok));

        assertThat(v.path("id").asText()).isEqualTo(WorldIds.AREA);
        assertThat(v.path("versions").has(WorldIds.AREA)).isTrue();
        assertThat(v.path("versions").has(WorldIds.ORBITER)).isTrue();
        assertThat(v.path("versions").has(WorldIds.PILLAR)).isTrue();
        java.util.Map<String, Long> versions = new java.util.HashMap<>();
        v.path("versions").properties().forEach(e -> versions.put(e.getKey(), e.getValue().asLong()));
        assertThat(v.path("checksum").asText()).isEqualTo(com.pessimisticit.scarcity.resourcemodel.VersionChecksum
                .hex(com.pessimisticit.scarcity.resourcemodel.VersionChecksum.of(versions)));
        assertThat(v.path("logSeq").isNumber()).as("the seeded area has a log head").isTrue();
        assertThat(get(port, "/api/world/resources/" + WorldIds.AREA + "/versions", null).statusCode())
                .isEqualTo(401);
    }
}
