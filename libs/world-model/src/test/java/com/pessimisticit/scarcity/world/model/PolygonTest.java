package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An entity with a polygon instead of a radius: walls (#294, docs/resources.md "Shapes", "Walls"). */
class PolygonTest {

    private static final String ID = "entity:00000000-0000-4000-8000-000000000009";

    private static final String EXAMPLE = """
            {"resourceKind":"entity","id":"entity:00000000-0000-4000-8000-000000000009","version":1,
             "children":[],"entityKind":"fixed","prototype":"wall","name":"granite wall",
             "material":"granite","mass":8100000.0,
             "polygon":[{"x":0.0,"y":0.0},{"x":2.0,"y":0.0},{"x":2.0,"y":0.5},{"x":0.0,"y":0.5}],
             "position":{"x":1.0,"y":0.25},"overlap":false,"damageable":false}""";

    private static List<Position> square() {
        return List.of(new Position(0, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2));
    }

    private static Entity wall(List<Position> polygon) {
        return Materials.get("granite").wall(polygon);
    }

    @Test
    void theDocumentedExampleRoundTrips() {
        Entity wall = WorldJson.fromJson(EXAMPLE, Entity.class);

        assertThat(wall).isInstanceOf(Fixed.class);
        assertThat(wall.radius()).isNull();
        assertThat(WorldJson.readTree(WorldJson.toJson(wall))).isEqualTo(WorldJson.readTree(EXAMPLE));
        assertThat(wall.requireStored()).isSameAs(wall);
    }

    @Test
    void theDocumentedExampleIsAGraniteWallOfThatPolygon() {
        Entity example = WorldJson.fromJson(EXAMPLE, Entity.class);
        Entity made = Materials.get("granite").wall(example.polygon());

        // 2.7 t/m3 x 1 m2 x 3 m high.
        assertThat(made.mass()).isEqualTo(8100000.0);
        Entity stored = made.withId(ID).withVersion(1).withChildren(List.of());
        assertThat(stored).isEqualTo(example);
    }

    @Test
    void thePrototypeHasNoPolygonPositionRadiusMaterialOrMass() {
        Entity prototype = Prototypes.get("wall");

        assertThat(prototype.entityKind()).isEqualTo(EntityKind.FIXED);
        assertThat(prototype.material()).isNull();
        assertThat(prototype.mass()).isNull();
        assertThat(prototype.overlap()).isFalse();
        assertThat(prototype.polygon()).isNull();
        assertThat(prototype.position()).isNull();
        assertThat(prototype.radius()).isNull();
        assertThat(prototype.shape()).isNull();
    }

    @Test
    void thePositionIsTheCentroid() {
        assertThat(wall(square()).position()).isEqualTo(new Position(1.0, 1.0));
        Entity triangle = wall(List.of(new Position(0, 0), new Position(3, 0), new Position(0, 3)));
        assertThat(triangle.position()).isEqualTo(new Position(1.0, 1.0));
    }

    @Test
    void aStoredPositionThatDisagreesIsRewritten() {
        assertThat(wall(square()).withPosition(new Position(50, 50)).position()).isEqualTo(new Position(1.0, 1.0));
        Entity read = WorldJson.fromJson(
                EXAMPLE.replace("\"position\":{\"x\":1.0,\"y\":0.25}", "\"position\":{\"x\":9.0,\"y\":9.0}"),
                Entity.class);
        assertThat(read.position()).isEqualTo(new Position(1.0, 0.25));
    }

    @Test
    void radiusAndPolygonTogetherAreRejected() {
        String json = EXAMPLE.replace("\"mass\"", "\"radius\":0.5,\"mass\"");

        assertThatThrownBy(() -> WorldJson.fromJson(json, Entity.class))
                .hasMessageContaining("polygon: an entity has a radius or a polygon, not both");
        assertThatThrownBy(() -> wall(square()).withRadius(0.5)).hasMessageStartingWith("polygon:");
        assertThatThrownBy(() -> Prototypes.get("pillar").withPolygon(square())).hasMessageStartingWith("polygon:");
    }

    @Test
    void aStoredPhysicalEntityWithNeitherIsRejected() {
        Entity neither = Prototypes.get("wall").withMass(1.0).withPosition(new Position(1, 1)).withId(ID).withVersion(1);

        assertThatThrownBy(neither::requireStored).hasMessageStartingWith("radius: required, or a polygon");
    }

    @Test
    void collinearVerticesOnAConvexPolygonAreAllowed() {
        Entity wall = wall(List.of(
                new Position(0, 0), new Position(1, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2)));

        assertThat(wall.polygon()).hasSize(5);
    }

    @Test
    void tooFewVerticesAreRejected() {
        List<Position> two = List.of(new Position(0, 0), new Position(1, 0));

        assertThatThrownBy(() -> wall(two)).hasMessageStartingWith("polygon: needs 3 or more");
    }

    @Test
    void clockwiseIsRejected() {
        List<Position> clockwise = List.of(new Position(0, 0), new Position(0, 2), new Position(2, 2), new Position(2, 0));

        assertThatThrownBy(() -> wall(clockwise)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void concaveIsRejected() {
        List<Position> arrow = List.of(
                new Position(0, 0), new Position(4, 0), new Position(2, 1), new Position(4, 4), new Position(0, 4));

        assertThatThrownBy(() -> wall(arrow)).hasMessageStartingWith("polygon: must be convex");
    }

    @Test
    void aSelfCrossingStarIsRejected() {
        List<Position> star = List.of(
                new Position(0, 2), new Position(-1.2, -1.6), new Position(1.9, 0.6),
                new Position(-1.9, 0.6), new Position(1.2, -1.6));

        assertThatThrownBy(() -> wall(star)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void aDegenerateLineIsRejected() {
        List<Position> line = List.of(new Position(0, 0), new Position(1, 0), new Position(2, 0));

        assertThatThrownBy(() -> wall(line)).hasMessageStartingWith("polygon: must be");
    }

    @Test
    void equalVerticesAreRejected() {
        List<Position> repeated = List.of(
                new Position(0, 0), new Position(2, 0), new Position(2, 0), new Position(2, 2), new Position(0, 2));

        assertThatThrownBy(() -> wall(repeated)).hasMessageStartingWith("polygon: no two");
    }

    @Test
    void theShapeIsDerivedFromTheFlatKeys() {
        Entity pillar = Prototypes.get("pillar").withPosition(new Position(3, 4));

        assertThat(pillar.shape()).isEqualTo(new Shape.Circle(new Position(3, 4), 0.5));
        assertThat(pillar.shape().boundingBox()).isEqualTo(new Shape.BoundingBox(2.5, 3.5, 3.5, 4.5));
        assertThat(wall(square()).shape()).isEqualTo(new Shape.Polygon(square()));
        assertThat(wall(square()).shape().boundingBox()).isEqualTo(new Shape.BoundingBox(0, 0, 2, 2));
    }
}
