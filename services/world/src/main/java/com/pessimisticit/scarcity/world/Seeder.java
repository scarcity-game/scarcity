package com.pessimisticit.scarcity.world;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

/**
 * Seeds the test game at startup: one game, one 10x8 area, a fixed pillar and a mobile
 * orbiter. Idempotent: if the game exists nothing is written (no version bumps on restart).
 * Runs after Flyway (which runs during context start).
 */
@Component
public class Seeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResourceStore store;
    private final TransactionTemplate tx;

    public Seeder(ResourceStore store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** @return true if it seeded, false if the game already existed. */
    public boolean seed() {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                if (store.exists(WorldIds.GAME)) {
                    upgradeOrbiter();
                    return false;
                }
                create(WorldIds.GAME, ResourceType.GAME, null,
                        "{\"name\":\"Test area\"}");
                create(WorldIds.AREA, ResourceType.AREA, WorldIds.GAME,
                        "{\"name\":\"Test room\",\"width\":10.0,\"height\":8.0}");
                create(WorldIds.PILLAR, ResourceType.ENTITY, WorldIds.AREA,
                        "{\"kind\":\"fixed\",\"name\":\"pillar\",\"position\":{\"x\":5.0,\"y\":4.0}}");
                create(WorldIds.ORBITER, ResourceType.ENTITY, WorldIds.AREA,
                        "{\"kind\":\"mobile\",\"name\":\"orbiter\",\"position\":{\"x\":8.0,\"y\":4.0},\"speed\":0.5,\"actions\":[\"orbit-step\",\"wait\"],\"behaviour\":{\"kind\":\"random\",\"choices\":[{\"weight\":1.0,\"action\":{\"type\":\"orbit-step\",\"params\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"arc\":0.5}}},{\"weight\":1.0,\"action\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}]}}" );
                log.info("seeded test game {}", WorldIds.GAME);
                return true;
            }));
        } catch (DuplicateKeyException e) {
            // Another replica seeded between our exists() and insert; its transaction won.
            log.info("test game was seeded concurrently; nothing to do");
            return false;
        }
    }

    private static ObjectNode randomBehaviour(JsonNode orbitStepParams) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "random");
        var choices = root.putArray("choices");
        var orbitChoice = choices.addObject();
        orbitChoice.put("weight", 1.0);
        var orbitAction = orbitChoice.putObject("action");
        orbitAction.put("type", "orbit-step");
        orbitAction.set("params", orbitStepParams.deepCopy());
        var waitChoice = choices.addObject();
        waitChoice.put("weight", 1.0);
        var waitAction = waitChoice.putObject("action");
        waitAction.put("type", "wait");
        var waitParams = waitAction.putObject("params");
        waitParams.put("duration", 1.0);
        return root;
    }

    /**
    * The 2026-11-11 deployment seeded an orbiter with an {@code orbit} key and no behaviour.
    * Bring it to the action-system shape in one update, keeping its position. Idempotent:
    * only an orbiter that still has {@code orbit} is touched.
    * The 2026-11-12 'repeat' shape is also upgraded.
    */
    private void upgradeOrbiter() {
        Resource orbiter = store.get(WorldIds.ORBITER).orElse(null);
        if (orbiter == null) {
            return;
        }
        JsonNode stateNode = orbiter.state();
        ObjectNode newState = stateNode.deepCopy();
        boolean updated = false;
        // Case A: old orbit key
        if (stateNode.has("orbit")) {
            JsonNode center = stateNode.path("orbit").path("center");
            double radius = stateNode.path("orbit").path("radius").asDouble(3.0);
            newState.remove("orbit");
            newState.put("speed", 0.5);
            newState.putArray("actions").add("orbit-step").add("wait");
            ObjectNode orbitStepParams = MAPPER.createObjectNode();
            orbitStepParams.set("center", center.isObject() ? center.deepCopy() : MAPPER.createObjectNode().put("x", 5.0).put("y", 4.0));
            orbitStepParams.put("radius", radius);
            orbitStepParams.put("arc", 0.5);
            newState.set("behaviour", randomBehaviour(orbitStepParams));
            updated = true;
        }
        // Case B: old repeat behaviour
        else if (stateNode.path("behaviour").path("kind").asText().equals("repeat")) {
            JsonNode paramsNode = stateNode.path("behaviour").path("action").path("params");
            newState.putArray("actions").add("orbit-step").add("wait");
            newState.set("behaviour", randomBehaviour(paramsNode));
            updated = true;
        }
        if (updated) {
            store.updateState(WorldIds.ORBITER, newState);
            log.info("upgraded {} to the action system", WorldIds.ORBITER);
        }
    }

    private void create(String id, ResourceType type, String parent, String stateJson) {
        try {
            JsonNode state = MAPPER.readTree(stateJson);
            store.create(new Resource(id, type, 1, List.of(), (ObjectNode) state), parent);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
