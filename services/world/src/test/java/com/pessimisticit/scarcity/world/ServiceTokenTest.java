package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.seed.WorldIds;
import java.net.http.HttpResponse;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import org.junit.jupiter.api.Test;

/** Service principals' JWTs, {@code kind=service} (root AGENTS.md, "Auth"; #147). */
class ServiceTokenTest extends WorldTest {

    @Test
    void aServiceTokenPostsForTheEntityItsPrincipalControls() throws Exception {
        HttpResponse<String> r = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        // The orbiter is the only controlled entity: idle again when its wait ends.
        assertThat(json(r).path("time").asDouble()).isEqualTo(1.0);
        assertThat(area().time()).isEqualTo(1.0);
        // It is up again, from the end of its wait (#148).
        Turn up = area().activeEntity();
        assertThat(up.entity()).isEqualTo(WorldIds.ORBITER);
        assertThat(up.controller()).isEqualTo(new Controller.Service("orbital"));
        assertThat(up.since()).isEqualTo(1.0);
        assertThat(area().pending()).isEmpty();
    }

    @Test
    void theIntentIsLoggedAsByTheServicePrincipal() throws Exception {
        assertThat(postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL).statusCode()).isEqualTo(200);
        var entries = json(get(port, "/api/world/log?area=" + WorldIds.AREA + "&kind=intent", ORBITAL))
                .path("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("controller").path("kind").asText()).isEqualTo("service");
        assertThat(entries.get(0).path("controller").path("principal").asText()).isEqualTo("orbital");
    }

    @Test
    void aServiceTokenReadsLikeAUser() throws Exception {
        assertThat(get(port, "/api/world/resources?type=game", ORBITAL).statusCode()).isEqualTo(200);
        assertThat(get(port, "/api/world/resources/" + WorldIds.ORBITER, STRANGER).statusCode()).isEqualTo(200);
    }

    @Test
    void aServiceMayNotPostForAnEntityItsPrincipalDoesNotControl() throws Exception {
        insertHero();
        assertThat(postIntent(waitIntent(WorldIds.ORBITER, 1.0), STRANGER).statusCode()).isEqualTo(403);
        // HERO is controlled by a user: no service may play it, even one whose sub is that user's.
        assertThat(postIntent(waitIntent(HERO, 1.0), ORBITAL).statusCode()).isEqualTo(403);
        assertThat(postIntent(waitIntent(HERO, 1.0), TestSupport.serviceToken(SUB)).statusCode()).isEqualTo(403);
        assertThat(resource(HERO).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM area_log", Long.class)).isZero();
    }

    @Test
    void aUserTokenWhoseSubIsAPrincipalIsNotThatService() throws Exception {
        // No kind, and kind=user: both are the user "orbital", which doesn't control the orbiter.
        for (String token : new String[] {TestSupport.userToken("orbital"), TestSupport.serviceToken("orbital", "user")}) {
            assertThat(postIntent(waitIntent(WorldIds.ORBITER, 1.0), token).statusCode()).isEqualTo(403);
        }
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(1);
    }

    @Test
    void anUnknownKindIsA401() throws Exception {
        String admin = TestSupport.serviceToken("orbital", "admin");
        assertThat(postIntent(waitIntent(WorldIds.ORBITER, 1.0), admin).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/resources?type=game", admin).statusCode()).isEqualTo(401);
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(1);
    }

    @Test
    void anApiKeyIsAPlain401() throws Exception {
        HttpResponse<String> r = postIntentWith(waitIntent(WorldIds.ORBITER, 1.0),
                "X-Api-Key", "orbital-test-key-5f0c2a9e41d7");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEmpty();
        assertThat(resource(WorldIds.ORBITER).version()).isEqualTo(1);
    }
}
