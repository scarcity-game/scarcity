package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The test room of the seed (10 x 8, the pillar at (5, 4), the orbiter at
 * (8, 4) played by orbital) and a rat whose behaviour each test picks.
 */
final class Room {

    static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";
    static final String RAT = "entity:00000000-0000-4000-8000-000000000009";
    static final Controller ORBITAL = new Controller.Service("orbital");
    private static final ObjectMapper PLAIN = new ObjectMapper();

    static final String PROJECTILE = """
            {"state":{"kind":"mobile","name":"pebble","speed":6.0,"actions":["fly","decay"]},"speed":6.0,
             "damage":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
             "debris":{"kind":"fixed","name":"rubble","actions":["decay"]},"decay":{"duration":20.0}}""";

    private Room() {
    }

    static ObjectNode object(String json) {
        try {
            return (ObjectNode) PLAIN.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static EntityState state(String json) {
        return WorldJson.fromJson(object(json), EntityState.class);
    }

    static Area area(double time) {
        return new Area(AREA, 5, new AreaState("Test room", 10.0, 8.0, time));
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

    /** A rat at (2, 2) that can wait and step, with {@code behaviour} (JSON; null: none). */
    static EntityState rat(String behaviour) {
        String b = behaviour == null ? "" : ",\"behaviour\":" + behaviour;
        return state("""
                {"kind":"mobile","name":"rat","position":{"x":2.0,"y":2.0},"speed":1.0,
                 "actions":["wait","step"]%s}""".formatted(b));
    }

    static String repeatWait(double duration) {
        return "{\"kind\":\"repeat\",\"action\":" + waitJson(duration) + "}";
    }

    static String waitJson(double duration) {
        return "{\"type\":\"wait\",\"params\":{\"duration\":" + duration + "}}";
    }

    static ActionSpec waitFor(double duration) {
        return new ActionSpec("wait", object("{\"duration\":" + duration + "}"));
    }

    static ActionSpec stepTo(double x, double y) {
        return new ActionSpec("step", object("{\"to\":{\"x\":" + x + ",\"y\":" + y + "}}"));
    }

    static ActionSpec throwAt(double x, double y, double spread) {
        return new ActionSpec("throw", object("{\"at\":{\"x\":" + x + ",\"y\":" + y + "},\"spread\":" + spread
                + ",\"recovery\":2.0,\"projectile\":" + PROJECTILE + "}"));
    }

    static Intent intent(ActionSpec action) {
        return Intent.of(ORBITER, action, ORBITAL);
    }
}
