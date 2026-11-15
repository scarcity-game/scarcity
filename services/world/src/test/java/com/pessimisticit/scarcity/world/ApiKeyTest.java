package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** {@code X-Api-Key} for service principals (root AGENTS.md, "Auth"; #113). */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyTest extends WorldTest {

    private static final String KEY = ApiKeyFilter.HEADER;

    @Test
    void aValidKeyPostsForTheEntityItsPrincipalControls() throws Exception {
        HttpResponse<String> r = postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, ORBITAL_KEY);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        // The orbiter is the only controlled entity: idle again when its wait ends.
        assertThat(json(r).path("time").asDouble()).isEqualTo(1.0);
        assertThat(resource(WorldIds.AREA).state().path("time").asDouble()).isEqualTo(1.0);
    }

    @Test
    void theIntentIsLoggedAsByTheServicePrincipal() throws Exception {
        assertThat(postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, ORBITAL_KEY).statusCode())
                .isEqualTo(200);
        var entries = json(get(port, "/api/world/log?area=" + WorldIds.AREA + "&kind=intent", null,
                KEY, ORBITAL_KEY)).path("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("by").path("kind").asText()).isEqualTo("service");
        assertThat(entries.get(0).path("by").path("principal").asText()).isEqualTo("orbital");
    }

    @Test
    void aValidKeyReadsLikeAUser() throws Exception {
        assertThat(get(port, "/api/world/resources?type=game", null, KEY, ORBITAL_KEY).statusCode())
                .isEqualTo(200);
        assertThat(get(port, "/api/world/resources/" + WorldIds.ORBITER, null, KEY, STRANGER_KEY).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aKeyMayNotPostForAnEntityItsPrincipalDoesNotControl() throws Exception {
        insertHero();
        assertThat(postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, STRANGER_KEY).statusCode())
                .isEqualTo(403);
        // HERO is controlled by a user: no service may play it.
        assertThat(postIntentWith(waitIntent(HERO, 1.0), KEY, ORBITAL_KEY).statusCode()).isEqualTo(403);
        assertThat(resource(HERO).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM area_log", Long.class)).isZero();
    }

    @Test
    void aWrongOrEmptyKeyIsA401WithAnEmptyBody() throws Exception {
        for (String bad : new String[] {"", "not-a-key", ORBITAL_KEY + "x", ORBITAL_KEY.toUpperCase()}) {
            HttpResponse<String> r = postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, bad);
            assertThat(r.statusCode()).as("key '%s'", bad).isEqualTo(401);
            assertThat(r.body()).isEmpty();
            assertThat(get(port, "/api/world/resources?type=game", null, KEY, bad).statusCode()).isEqualTo(401);
        }
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(1);
    }

    @Test
    void aKeyAndAJwtTogetherAreA400() throws Exception {
        HttpResponse<String> r = postIntentWith(waitIntent(WorldIds.ORBITER, 1.0),
                KEY, ORBITAL_KEY, "Authorization", "Bearer " + TestSupport.validToken());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEmpty();
        assertThat(get(port, "/api/world/resources?type=game", TestSupport.validToken(), KEY, ORBITAL_KEY)
                .statusCode()).isEqualTo(400);
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(1);
    }

    @Test
    void theKeyTwiceIsA400() throws Exception {
        assertThat(postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, ORBITAL_KEY, KEY, ORBITAL_KEY)
                .statusCode()).isEqualTo(400);
    }

    @Test
    void noKeyEverReachesTheLogs(CapturedOutput output) throws Exception {
        postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, ORBITAL_KEY);
        postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, STRANGER_KEY);
        postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, "wrong-key-" + ORBITAL_KEY);
        postIntentWith(waitIntent(WorldIds.ORBITER, 1.0), KEY, ORBITAL_KEY,
                "Authorization", "Bearer " + TestSupport.validToken());
        assertThat(output.getAll())
                .contains("api-key accepted for principal orbital")
                .contains("api-key rejected from 127.0.0.1")
                .doesNotContain(ORBITAL_KEY)
                .doesNotContain(STRANGER_KEY);
    }
}
