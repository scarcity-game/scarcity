package com.pessimisticit.scarcity.world.model.rules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Map;

/** The test room of the seed: 10 x 8, the pillar at (5, 4) with radius 0.5, the orbiter at (8, 4). */
final class TestArea {

    static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";
    static final Area ROOM = new Area("Test room", 10.0, 8.0, 10.0);
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

    static Map<String, Entity> seed() {
        return Map.of(PILLAR, pillar(), ORBITER, orbiter());
    }

    static AreaView view(Map<String, Entity> entities) {
        return AreaView.of(FakeContext.AREA, ROOM, entities);
    }

    static Actor actor(String id, Entity self, Map<String, Entity> others) {
        java.util.Map<String, Entity> all = new java.util.HashMap<>(others);
        all.put(id, self);
        return new Actor(id, self, view(all));
    }

    /** The seed's projectile: a pebble, rubble that decays in 20. */
    static String projectileJson() {
        return """
                {"state":{"prototype":"pebble"},"speed":6.0,
                 "damageRoll":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
                 "debris":{"prototype":"rubble"},"decay":{"duration":20.0}}""";
    }

    static Projectile projectile() {
        return WorldJson.fromJson(tree(projectileJson()), Projectile.class);
    }
}
