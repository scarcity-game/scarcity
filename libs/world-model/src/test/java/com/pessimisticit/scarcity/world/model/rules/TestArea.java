package com.pessimisticit.scarcity.world.model.rules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Map;

/** The test room of the seed: 10 x 8 inside four stone walls, the pillar at (5, 4) with radius 0.5, the orbiter at (8, 4). */
final class TestArea {

    static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";
    static final double WIDTH = 10.0;
    static final double HEIGHT = 8.0;
    static final Area ROOM = new Area("Test room", 10.0);
    static final ObjectMapper PLAIN = new ObjectMapper();

    private TestArea() {
    }

    static Entity state(String json) {
        return WorldJson.fromJson(tree(json), Entity.class);
    }

    static JsonNode tree(String json) {
        return PLAIN.readTree(json);
    }

    static ObjectNode object(String json) {
        return (ObjectNode) tree(json);
    }

    static Entity pillar() {
        return state("""
                {"resourceKind":"entity","entityKind":"fixed","prototype":"pillar","name":"pillar","position":{"x":5.0,"y":4.0},"radius":0.5,"hp":100.0,"overlap":false,"damageable":true}""");
    }

    static Entity orbiter() {
        return state("""
                {"resourceKind":"entity","entityKind":"npc","prototype":"orbiter","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,
                 "actions":["step","wait","throw"],"controller":{"kind":"service","principal":"orbital"}}""");
    }

    static final String WALL = "entity:00000000-0000-4000-8000-0000000000b1";

    /** A granite wall, the rectangle from (x0, y0) to (x1, y1). */
    static Entity wall(double x0, double y0, double x1, double y1) {
        return com.pessimisticit.scarcity.world.model.Materials.get("granite").wall(java.util.List.of(
                new com.pessimisticit.scarcity.world.model.Position(x0, y0),
                new com.pessimisticit.scarcity.world.model.Position(x1, y0),
                new com.pessimisticit.scarcity.world.model.Position(x1, y1),
                new com.pessimisticit.scarcity.world.model.Position(x0, y1)));
    }

    /** The four walls, 0.5 thick, outside [0, 10] x [0, 8], overlapping at the corners. */
    static Map<String, Entity> walls() {
        double t = 0.5;
        return Map.of(
                "entity:00000000-0000-4000-8000-0000000000c1", wall(-t, -t, WIDTH + t, 0.0),
                "entity:00000000-0000-4000-8000-0000000000c2", wall(WIDTH, -t, WIDTH + t, HEIGHT + t),
                "entity:00000000-0000-4000-8000-0000000000c3", wall(-t, HEIGHT, WIDTH + t, HEIGHT + t),
                "entity:00000000-0000-4000-8000-0000000000c4", wall(-t, -t, 0.0, HEIGHT + t));
    }

    /** {@code entities} with the room's walls. */
    static Map<String, Entity> enclosed(Map<String, Entity> entities) {
        Map<String, Entity> all = new java.util.HashMap<>(walls());
        all.putAll(entities);
        return all;
    }

    static Map<String, Entity> seed() {
        return Map.of(PILLAR, pillar(), ORBITER, orbiter());
    }

    static AreaView view(Map<String, Entity> entities) {
        return AreaView.of(FakeContext.AREA, ROOM, enclosed(entities));
    }

    static Actor actor(String id, Entity self, Map<String, Entity> others) {
        java.util.Map<String, Entity> all = new java.util.HashMap<>(others);
        all.put(id, self);
        return new Actor(id, self, view(all));
    }

    /** The seed's projectile: a pebble, which lands as a pebble or rubble. */
    static String projectileJson() {
        return """
                {"state":{"prototype":"pebble"},"speed":6.0,
                 "damageRoll":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
                 "item":{"prototype":"pebble"},"debris":{"prototype":"rubble"}}""";
    }

    static Projectile projectile() {
        return WorldJson.fromJson(tree(projectileJson()), Projectile.class);
    }
}
