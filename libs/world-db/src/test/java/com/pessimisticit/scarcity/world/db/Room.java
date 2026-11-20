package com.pessimisticit.scarcity.world.db;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.engine.Intent;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A test room in the database, with fresh ids: 10 x 8, a pillar at (5, 4),
 * the orbiter at (8, 4) played by orbital, and a rat that waits 1.0 again
 * and again (no draws).
 */
final class Room {

    static final Controller ORBITAL = new Controller.Service("orbital");
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static final String PROJECTILE = """
            {"state":{"prototype":"pebble"},"speed":6.0,
             "damageRoll":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
             "debris":{"prototype":"rubble"},"decay":{"duration":20.0}}""";

    final String area;
    final String pillar;
    final String orbiter;
    final String rat;

    Room(ResourceStore resourceStore, TransactionTemplate tx) {
        this.area = Ids.newId(ResourceKind.AREA);
        this.pillar = Ids.newId(ResourceKind.ENTITY);
        this.orbiter = Ids.newId(ResourceKind.ENTITY);
        this.rat = Ids.newId(ResourceKind.ENTITY);
        tx.executeWithoutResult(s -> {
            resourceStore.create(new Area("Test room", 10.0, 8.0, 0.0).withId(area), null);
            resourceStore.create(entity(pillar, """
                    {"resourceKind":"entity","entityKind":"fixed","prototype":"pillar","name":"pillar",
                     "position":{"x":5.0,"y":4.0},"mass":6360000.0,"radius":0.5,"hp":100.0,"overlap":false,"damageable":true}"""), area);
            resourceStore.create(entity(orbiter, """
                    {"resourceKind":"entity","entityKind":"npc","prototype":"orbiter","name":"orbiter",
                     "position":{"x":8.0,"y":4.0},"mass":70000.0,"speed":0.5,"radius":0.3,"actions":["step","wait","throw"],
                     "controller":{"kind":"service","principal":"orbital"}}"""), area);
            resourceStore.create(entity(rat, """
                    {"resourceKind":"entity","entityKind":"mob","prototype":"rat","name":"rat",
                     "position":{"x":2.0,"y":2.0},"mass":300.0,"speed":1.0,"radius":0.1,"actions":["wait"],
                     "behaviour":{"kind":"repeat","actionSpec":{"type":"wait","params":{"duration":1.0}}}}"""), area);
        });
    }

    private static Entity entity(String id, String json) {
        return WorldJson.fromJson(object(json), Entity.class).withId(id);
    }

    static ObjectNode object(String json) {
        return (ObjectNode) PLAIN.readTree(json);
    }

    Intent waitFor(double duration) {
        return Intent.of(orbiter, new ActionSpec("wait", object("{\"duration\":" + duration + "}")), ORBITAL);
    }

    /** A throw at the pillar: recovery 2.0, the pebble lands at 10/24 and breaks into rubble that decays at 20 + 10/24. */
    Intent throwAtPillar() {
        return Intent.of(orbiter, new ActionSpec("throw", object("{\"at\":{\"x\":5.0,\"y\":4.0},\"spread\":0.1,"
                + "\"recovery\":2.0,\"projectile\":" + PROJECTILE + "}")), ORBITAL);
    }

    /** The draws of {@link #throwAtPillar()}: no scatter, damage 2.2, the pebble breaks. */
    static ScriptedRandom hitAndBreak() {
        return new ScriptedRandom().normal(0.0, 0.4).uniform(0.1);
    }
}
