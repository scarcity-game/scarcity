package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The wall entity kind (#294, docs/resources.md "Walls"). */
class WallTest {

    private static final String EXAMPLE = """
            {"resourceKind":"entity","id":"entity:00000000-0000-4000-8000-000000000009","version":1,
             "children":[],"entityKind":"wall","prototype":"stone-wall","name":"stone wall",
             "material":"stone",
             "polygon":[{"x":0.0,"y":0.0},{"x":2.0,"y":0.0},{"x":2.0,"y":0.5},{"x":0.0,"y":0.5}],
             "position":{"x":1.0,"y":0.25},"overlap":false,"damageable":false}""";

    private static List<Position> square() {
        return List.of(new Position(0, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2));
    }

    @Test
    void theDocumentedExampleRoundTrips() {
        Entity wall = WorldJson.fromJson(EXAMPLE, Entity.class);

        assertThat(wall).isInstanceOf(Wall.class);
        assertThat(wall.entityKind()).isEqualTo(EntityKind.WALL);
        assertThat(WorldJson.readTree(WorldJson.toJson(wall))).isEqualTo(WorldJson.readTree(EXAMPLE));
        assertThat(wall.requireStored()).isSameAs(wall);
    }

    @Test
    void thePrototypeHasNoPolygonAndNoPosition() {
        Wall prototype = (Wall) Prototypes.get("stone-wall");

        assertThat(prototype.material()).isEqualTo("stone");
        assertThat(prototype.name()).isEqualTo("stone wall");
        assertThat(prototype.polygon()).isNull();
        assertThat(prototype.position()).isNull();
        assertThat(prototype.radius()).isNull();
        assertThat(prototype.physical()).isFalse();
    }

    @Test
    void thePositionIsTheCentroid() {
        Wall wall = Wall.of("stone-wall", square());

        assertThat(wall.position()).isEqualTo(new Position(1.0, 1.0));
        Wall triangle = wall.withPolygon(List.of(new Position(0, 0), new Position(3, 0), new Position(0, 3)));
        assertThat(triangle.position()).isEqualTo(new Position(1.0, 1.0));
    }

    @Test
    void aStoredPositionThatDisagreesIsRewritten() {
        Wall wall = Wall.of("stone-wall", square());

        assertThat(wall.withPosition(new Position(50, 50)).position()).isEqualTo(new Position(1.0, 1.0));
        Entity read = WorldJson.fromJson(
                EXAMPLE.replace("\"position\":{\"x\":1.0,\"y\":0.25}", "\"position\":{\"x\":9.0,\"y\":9.0}"),
                Entity.class);
        assertThat(read.position()).isEqualTo(new Position(1.0, 0.25));
    }

    @Test
    void aWallWithoutAPolygonIsNotStorable() {
        Wall prototype = (Wall) Prototypes.get("stone-wall");

        assertThatThrownBy(() -> prototype.withId("entity:00000000-0000-4000-8000-000000000009")
                .withVersion(1).requireStored()).hasMessageStartingWith("polygon: required");
    }

    @Test
    void collinearVerticesOnAConvexPolygonAreAllowed() {
        Wall wall = Wall.of("stone-wall", List.of(
                new Position(0, 0), new Position(1, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2)));

        assertThat(wall.polygon()).hasSize(5);
    }

    @Test
    void tooFewVerticesAreRejected() {
        List<Position> two = List.of(new Position(0, 0), new Position(1, 0));

        assertThatThrownBy(() -> Wall.of("stone-wall", two)).hasMessageStartingWith("polygon: needs 3 or more");
    }

    @Test
    void clockwiseIsRejected() {
        List<Position> clockwise = List.of(new Position(0, 0), new Position(0, 2), new Position(2, 2), new Position(2, 0));

        assertThatThrownBy(() -> Wall.of("stone-wall", clockwise)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void concaveIsRejected() {
        List<Position> arrow = List.of(
                new Position(0, 0), new Position(4, 0), new Position(2, 1), new Position(4, 4), new Position(0, 4));

        assertThatThrownBy(() -> Wall.of("stone-wall", arrow)).hasMessageStartingWith("polygon: must be convex");
    }

    @Test
    void aSelfCrossingStarIsRejected() {
        List<Position> star = List.of(
                new Position(0, 2), new Position(-1.2, -1.6), new Position(1.9, 0.6),
                new Position(-1.9, 0.6), new Position(1.2, -1.6));

        assertThatThrownBy(() -> Wall.of("stone-wall", star)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void allCollinearVerticesAreRejected() {
        List<Position> line = List.of(new Position(0, 0), new Position(1, 0), new Position(2, 0));

        assertThatThrownBy(() -> Wall.of("stone-wall", line)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void equalVerticesAreRejected() {
        List<Position> repeated = List.of(
                new Position(0, 0), new Position(2, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2));

        assertThatThrownBy(() -> Wall.of("stone-wall", repeated)).hasMessageStartingWith("polygon: no two");
    }

    @Test
    void aMoversKeysAreRejected() {
        Wall wall = Wall.of("stone-wall", square());

        assertThatThrownBy(() -> wall.withRadius(0.5)).hasMessageStartingWith("radius:");
        assertThatThrownBy(() -> wall.withMass(10.0)).hasMessageStartingWith("mass:");
        assertThatThrownBy(() -> wall.withSpeed(1.0)).hasMessageStartingWith("speed:");
        assertThatThrownBy(() -> wall.withActions(List.of("step"))).hasMessageStartingWith("actions:");
    }

    @Test
    void aRadiusInJsonIsRejected() {
        String json = EXAMPLE.replace("\"overlap\"", "\"radius\":0.5,\"overlap\"");

        assertThatThrownBy(() -> WorldJson.fromJson(json, Entity.class)).hasMessageContaining("radius:");
    }

    @Test
    void overlapAndDamageableAreRejected() {
        String overlapping = EXAMPLE.replace("\"overlap\":false", "\"overlap\":true");
        String damageable = EXAMPLE.replace("\"damageable\":false", "\"damageable\":true,\"hp\":5.0");

        assertThatThrownBy(() -> WorldJson.fromJson(overlapping, Entity.class)).hasMessageContaining("overlap:");
        assertThatThrownBy(() -> WorldJson.fromJson(damageable, Entity.class)).hasMessageContaining("hp:");
    }

    @Test
    void aNonWallPrototypeIsNotAWall() {
        assertThatThrownBy(() -> Wall.of("pillar", square())).hasMessageStartingWith("prototype: not a wall");
    }
}
