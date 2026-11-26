package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * An intelligence service as a whole ({@code controller.mode: endpoint}) with one choice, wait:
 * a turn gives an intent, and only world's token gets one. Nothing of socket mode is there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "controller.mode=endpoint",
        "controller.principal=tester",
        "controller.seed=1",
        "controller.weights.wait=1",
        "management.endpoints.web.exposure.include=health",
        "management.server.port=0"})
class TurnEndpointTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000004";
    private static final String WALL = "entity:00000000-0000-4000-8000-000000000005";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final TestJwks JWKS;

    static {
        try {
            JWKS = new TestJwks();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        Choice waitChoice() {
            return Fixtures.named("wait");
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("controller.jwk-set-uri", JWKS::url);
    }

    @AfterAll
    static void stop() {
        JWKS.close();
    }

    @Test
    void aTurnFromWorldGivesAnIntentWithItsDraws() throws Exception {
        HttpResponse<String> response = post(JWKS.worldToken(), body(List.of(), List.of()));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode answer = WorldJson.readTree(response.body());
        assertThat(answer.at("/actionSpec/type").asString()).isEqualTo("wait");
        assertThat(answer.at("/reported/draw/dist").asString()).isEqualTo("categorical");
        assertThat(answer.has("entity")).isFalse();
        assertThat(answer.has("ifVersion")).isFalse();
    }

    @Test
    void aWallWhoseCellIsNotAmongTheCellsIsDroppedNotAnError() throws Exception {
        Cell cell = wallCell();
        Entity wall = Materials.get("granite").wall(cell).withId(WALL);

        // The wall names a cell the request doesn't carry.
        HttpResponse<String> response = post(JWKS.worldToken(), body(List.of(wall), List.of()));
        assertThat(response.statusCode()).isEqualTo(200);

        TurnRequest withoutCell = request(List.of(wall), List.of());
        assertThat(TurnEndpoint.situation(withoutCell).areaView().entities()).containsOnlyKeys(MOUSE);
        TurnRequest withCell = request(List.of(wall), List.of(cell));
        assertThat(TurnEndpoint.situation(withCell).areaView().entities()).containsOnlyKeys(MOUSE, WALL);
        assertThat(TurnEndpoint.situation(withCell).areaView().entities().get(WALL).shape()).isNotNull();
    }

    @Test
    void aTokenOfAnotherPrincipalOrAUserIsForbidden() throws Exception {
        String other = JWKS.token("orbital", "service", Instant.now().plusSeconds(900));
        String user = JWKS.token("world", null, Instant.now().plusSeconds(900));
        String userNamedLikeIt = JWKS.token("00000000-0000-4000-8000-000000000001", null, Instant.now().plusSeconds(900));

        assertThat(post(other, body(List.of(), List.of())).statusCode()).isEqualTo(403);
        assertThat(post(user, body(List.of(), List.of())).statusCode()).isEqualTo(403);
        assertThat(post(userNamedLikeIt, body(List.of(), List.of())).statusCode()).isEqualTo(403);
    }

    @Test
    void noTokenOrABadOneIsUnauthorized() throws Exception {
        assertThat(post(null, body(List.of(), List.of())).statusCode()).isEqualTo(401);
        assertThat(post("not-a-jwt", body(List.of(), List.of())).statusCode()).isEqualTo(401);
        assertThat(post(JWKS.forgedWorldToken(), body(List.of(), List.of())).statusCode()).isEqualTo(401);
        assertThat(post(JWKS.token("world", "service", Instant.now().minusSeconds(60)),
                body(List.of(), List.of())).statusCode()).isEqualTo(401);
    }

    @Test
    void aBodyThatIsNotATurnRequestIsABadRequest() throws Exception {
        assertThat(post(JWKS.worldToken(), "{\"area\":\"" + AREA + "\"}").statusCode()).isEqualTo(400);
        assertThat(post(JWKS.worldToken(), "not json").statusCode()).isEqualTo(400);
    }

    @Test
    void anEntityThatCannotWaitAndHasNoFeasibleChoiceGets422() throws Exception {
        Entity mute = mouse().withActions(List.of("step"));
        HttpResponse<String> response = post(JWKS.worldToken(), WorldJson.toJson(
                new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, mute, List.of(), List.of())));

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void thereIsNoSocketNoWorldClientAndNoServiceToken() {
        assertThat(context.getBeanNamesForType(ServiceToken.class)).isEmpty();
        assertThat(context.getBeanNamesForType(World.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TurnSocket.class)).isEmpty();
        assertThat(context.getBeanNamesForType(Turns.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ControllerProperties.class)).isEmpty();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/turn"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Entity mouse() {
        return Prototypes.get("mouse").withActions(List.of("step", "wait"))
                .withPosition(new Position(0.5, 0.5)).withId(MOUSE);
    }

    private static TurnRequest request(List<Entity> entities, List<Cell> cells) {
        return new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, mouse(), entities, cells);
    }

    private static String body(List<Entity> entities, List<Cell> cells) {
        return WorldJson.toJson(request(entities, cells));
    }

    /** Two 1 m squares side by side: floor at x 0..1, wall at x 1..2. */
    private static Cell wallCell() {
        Random rng = new Random(1);
        CellMesh mesh = CellMesh.generate(List.of(
                new CellMesh.Piece(List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1)), true),
                new CellMesh.Piece(List.of(new Position(1, 0), new Position(2, 0), new Position(2, 1), new Position(1, 1)), false)),
                () -> Ids.newId(ResourceKind.CELL, rng));
        return mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.WALL).findFirst().orElseThrow().withVersion(1);
    }
}
