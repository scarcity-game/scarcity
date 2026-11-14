package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** The test room of the seed: 10 x 8, the pillar at (5, 4) with radius 0.5, the orbiter at (8, 4). */
final class TestArea {

    static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";
    static final AreaState ROOM = new AreaState("Test room", 10.0, 8.0, 10.0);
    static final ObjectMapper PLAIN = new ObjectMapper();

    private TestArea() {
    }

    static EntityState state(String json) {
        return WorldJson.fromJson(tree(json), EntityState.class);
    }

    static JsonNode tree(String json) {
        try {
            return PLAIN.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectNode object(String json) {
        return (ObjectNode) tree(json);
    }

    static EntityState pillar() {
        return state("""
                {"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0},"radius":0.5,"hp":100.0}""");
    }

    static EntityState orbiter() {
        return state("""
                {"kind":"mobile","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,
                 "actions":["step","wait","throw"],"controller":{"kind":"service","principal":"orbital"}}""");
    }

    static Map<String, EntityState> seed() {
        return Map.of(PILLAR, pillar(), ORBITER, orbiter());
    }

    static AreaView view(Map<String, EntityState> entities) {
        return AreaView.of(FakeContext.AREA, ROOM, entities);
    }

    static Actor actor(String id, EntityState self, Map<String, EntityState> others) {
        java.util.Map<String, EntityState> all = new java.util.HashMap<>(others);
        all.put(id, self);
        return new Actor(id, self, view(all));
    }

    /** The seed's projectile: a pebble, rubble that decays in 20. */
    static String projectileJson() {
        return """
                {"state":{"kind":"mobile","name":"pebble","speed":6.0,"actions":["fly","decay"]},"speed":6.0,
                 "damage":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
                 "debris":{"kind":"fixed","name":"rubble","actions":["decay"]},"decay":{"duration":20.0}}""";
    }

    static Projectile projectile() {
        return WorldJson.fromJson(tree(projectileJson()), Projectile.class);
    }
}
