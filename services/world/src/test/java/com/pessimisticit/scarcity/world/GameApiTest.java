package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code POST /api/world/games} and {@code GET /api/world/games/profiles} (#455). */
class GameApiTest extends WorldTest {

    private static final String ARENA = "{\"profile\":\"arena\",\"options\":{\"dens\":["
            + "{\"prototype\":\"goblin\",\"count\":2},{\"prototype\":\"wild-dog\",\"count\":1}]}}";

    private HttpResponse<String> postGame(String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/games"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theProfilesListTheArenaAndItsMobs() throws Exception {
        HttpResponse<String> r = get(port, "/api/world/games/profiles", TestSupport.validToken());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode arena = json(r).path("profiles").path(0);
        assertThat(arena.path("name").asText()).isEqualTo("arena");
        assertThat(arena.path("options").path("maxDensPerMob").asInt()).isEqualTo(4);
        assertThat(StreamSupport.stream(arena.path("options").path("mobs").spliterator(), false)
                .map(m -> m.path("prototype").asText() + "/" + m.path("faction").asText()))
                .containsExactly("goblin/goblin", "wild-dog/beast");
    }

    @Test
    void anArenaIsMadeWithItsDensAndTheSpectatorUp() throws Exception {
        HttpResponse<String> r = postGame(ARENA, TestSupport.userToken(SUB));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode made = json(r);
        String gameId = made.path("game").asText();
        String areaId = made.path("area").asText();
        String spectatorId = made.path("entity").asText();
        assertThat(r.headers().firstValue("Location")).hasValue("/api/world/resources/" + gameId);

        HttpResponse<String> game = get(port, "/api/world/resources/" + gameId, TestSupport.validToken());
        assertThat(game.statusCode()).isEqualTo(200);
        assertThat(json(game).path("children").path(0).asText()).isEqualTo(areaId);
        assertThat(((Game) resource(gameId)).factions()).containsOnlyKeys("goblin", "beast");

        List<Entity> children = children(areaId);
        assertThat(children).filteredOn(e -> "den".equals(e.prototype())).hasSize(3);
        Entity spectator = entity(spectatorId);
        assertThat(spectator.controller()).isEqualTo(new Controller.User(SUB));
        assertThat(((Area) resource(areaId)).activeEntity().entity())
                .isEqualTo(spectatorId);

        JsonNode log = json(get(port, "/api/world/log?area=" + areaId, TestSupport.validToken()));
        JsonNode first = log.path("entries").path(0);
        assertThat(first.path("kind").asText()).isEqualTo("snapshot");
        assertThat(first.path("generation").path("profile").asText()).isEqualTo("arena");
        assertThat(first.path("draws").path(0).path("for").asText()).isEqualTo("den[0].x");
    }

    @Test
    void whenTheSpectatorWaitsTheDensSpawn() throws Exception {
        JsonNode made = json(postGame(ARENA, TestSupport.userToken(SUB)));
        String areaId = made.path("area").asText();
        HttpResponse<String> r = postIntent(waitIntent(made.path("entity").asText(), 60.0), TestSupport.userToken(SUB));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        List<String> dens = children(areaId).stream().filter(e -> "den".equals(e.prototype())).map(Entity::id).toList();
        List<Entity> mobs = children(areaId).stream().filter(e -> e.spawner() != null).toList();
        assertThat(mobs).isNotEmpty().allSatisfy(m -> {
            assertThat(dens).contains(m.spawner());
            assertThat(m.prototype()).isIn("goblin", "wild-dog");
        });
    }

    @Test
    void badOptionsAreA400AndWriteNothing() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM resources", Long.class);
        for (String body : List.of(
                "{\"profile\":\"arena\",\"options\":{\"dens\":[]}}",
                "{\"profile\":\"arena\",\"options\":{\"dens\":[{\"prototype\":\"pillar\",\"count\":1}]}}",
                "{\"profile\":\"arena\",\"options\":{\"dens\":[{\"prototype\":\"goblin\",\"count\":5}]}}",
                "{\"profile\":\"arena\",\"options\":{}}",
                "{\"profile\":\"dungeon\",\"options\":{}}",
                "{\"profile\":\"arena\"}",
                "{\"profile\":\"arena\",\"options\":{\"dens\":[]},\"extra\":1}",
                "not json")) {
            HttpResponse<String> r = postGame(body, TestSupport.userToken(SUB));
            assertThat(r.statusCode()).as(body + " -> " + r.body()).isEqualTo(400);
            assertThat(json(r).path("error").asText()).as(body).isNotBlank();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources", Long.class)).isEqualTo(before);
    }

    @Test
    void anonymousIsA401AndAServiceA403() throws Exception {
        assertThat(postGame(ARENA, null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/api/world/games/profiles", null).statusCode()).isEqualTo(401);
        assertThat(postGame(ARENA, ORBITAL).statusCode()).isEqualTo(403);
    }

    private List<Entity> children(String areaId) {
        return resource(areaId).children().stream().filter(id -> id.startsWith("entity:")).map(this::entity).toList();
    }
}
