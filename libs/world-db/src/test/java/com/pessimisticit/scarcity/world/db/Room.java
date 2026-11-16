package com.pessimisticit.scarcity.world.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceIds;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.world.engine.Intent;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
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
            {"state":{"entityKind":"mobile","name":"pebble","speed":6.0,"actions":["fly","decay"]},"speed":6.0,
             "damageRoll":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":0.5,
             "debris":{"entityKind":"fixed","name":"rubble","actions":["decay"]},"decay":{"duration":20.0}}""";

    final String area;
    final String pillar;
    final String orbiter;
    final String rat;

    Room(ResourceStore resourceStore, TransactionTemplate tx) {
        this.area = ResourceIds.newId(ResourceType.AREA);
        this.pillar = ResourceIds.newId(ResourceType.ENTITY);
        this.orbiter = ResourceIds.newId(ResourceType.ENTITY);
        this.rat = ResourceIds.newId(ResourceType.ENTITY);
        tx.executeWithoutResult(s -> {
            resourceStore.create(new Resource(area, ResourceType.AREA, 1, List.of(), object("""
                    {"name":"Test room","width":10.0,"height":8.0,"time":0.0}""")), null);
            resourceStore.create(entity(pillar, """
                    {"entityKind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0},"radius":0.5,"hp":100.0}"""), area);
            resourceStore.create(entity(orbiter, """
                    {"entityKind":"mobile","name":"orbiter","position":{"x":8.0,"y":4.0},"speed":0.5,
                     "actions":["step","wait","throw"],"controller":{"kind":"service","principal":"orbital"}}"""),
                    area);
            resourceStore.create(entity(rat, """
                    {"entityKind":"mobile","name":"rat","position":{"x":2.0,"y":2.0},"speed":1.0,"actions":["wait"],
                     "behaviour":{"kind":"repeat","actionSpec":{"type":"wait","params":{"duration":1.0}}}}"""), area);
        });
    }

    private static Resource entity(String id, String state) {
        return new Resource(id, ResourceType.ENTITY, 1, List.of(), object(state));
    }

    static ObjectNode object(String json) {
        try {
            return (ObjectNode) PLAIN.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
