package com.pessimisticit.scarcity.world.engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import java.util.Map;

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
            {"state":{"prototype":"pebble"},"speed":6.0,
             "damageRoll":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
             "item":{"prototype":"pebble"},"debris":{"prototype":"rubble"}}""";

    private Room() {
    }

    static ObjectNode object(String json) {
        return (ObjectNode) PLAIN.readTree(json);
    }

    static Entity state(String json) {
        return WorldJson.fromJson(object(json), Entity.class);
    }

    /** The room's east wall, from x 10 to 10.5. */
    static final String EAST_WALL = "entity:00000000-0000-4000-8000-0000000000c2";

    /** A granite wall, the rectangle from (x0, y0) to (x1, y1). */
    static Entity wall(double x0, double y0, double x1, double y1) {
        return Materials.get("granite").wall(List.of(new Position(x0, y0), new Position(x1, y0),
                new Position(x1, y1), new Position(x0, y1)));
    }

    /** The four walls of the 10 x 8 room, 0.5 thick, outside it, overlapping at the corners. */
    static Map<String, Entity> walls() {
        return Map.of(
                "entity:00000000-0000-4000-8000-0000000000c1", wall(-0.5, -0.5, 10.5, 0.0),
                EAST_WALL, wall(10.0, -0.5, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c3", wall(-0.5, 8.0, 10.5, 8.5),
                "entity:00000000-0000-4000-8000-0000000000c4", wall(-0.5, -0.5, 0.0, 8.5));
    }

    /** The room at {@code time}, enclosed by its walls and nothing else in it. */
    static Store walled(double time) {
        Store store = new Store(area(time));
        walls().forEach(store::put);
        return store;
    }

    static Area area(double time) {
        return new Area("Test room", time).withId(AREA).withVersion(5);
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

    /** A rat at (2, 2) that can wait and step, with {@code behaviour} (JSON; null: none). */
    static Entity rat(String behaviour) {
        String b = behaviour == null ? "" : ",\"behaviour\":" + behaviour;
        return state("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"rat","name":"rat","position":{"x":2.0,"y":2.0},"speed":1.0,
                 "actions":["wait","step"]%s}""".formatted(b));
    }

    static String repeatWait(double duration) {
        return "{\"kind\":\"repeat\",\"actionSpec\":" + waitJson(duration) + "}";
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
