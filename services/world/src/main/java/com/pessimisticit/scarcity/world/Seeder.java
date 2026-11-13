package com.pessimisticit.scarcity.world;

import java.io.IOException;
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
                    upgradePillar();
                    upgradeOrbiter();
                    return false;
                }
                // Fresh seed: create with states from resource files
                create(WorldIds.GAME, ResourceType.GAME, null,
                        "{\"name\":\"Test area\"}");
                create(WorldIds.AREA, ResourceType.AREA, WorldIds.GAME,
                        "{\"name\":\"Test room\",\"width\":10.0,\"height\":8.0}");
                create(WorldIds.PILLAR, ResourceType.ENTITY, WorldIds.AREA,
                        loadSeed("pillar"));
                create(WorldIds.ORBITER, ResourceType.ENTITY, WorldIds.AREA,
                        loadSeed("orbiter"));
                log.info("seeded test game {}", WorldIds.GAME);
                return true;
            }));
        } catch (DuplicateKeyException e) {
            // Another replica seeded between our exists() and insert; its transaction won.
            log.info("test game was seeded concurrently; nothing to do");
            return false;
        }
    }

    private ObjectNode loadSeed(String name) {
        try {
            return (ObjectNode) MAPPER.readTree(Seeder.class.getResourceAsStream("/seed/" + name + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upgradePillar() {
        Resource pillar = store.get(WorldIds.PILLAR).orElse(null);
        if (pillar == null) {
            return;
        }
        ObjectNode s = pillar.state().deepCopy();
        boolean changed = false;
        if (!s.path("radius").isNumber()) {
            s.put("radius", 0.5);
            changed = true;
        }
        if (!s.path("hp").isNumber()) {
            s.put("hp", 100.0);
            changed = true;
        }
        if (changed) {
            store.updateState(WorldIds.PILLAR, s);
            log.info("upgraded {} to include radius and hp", WorldIds.PILLAR);
        }
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
        JsonNode old = orbiter.state();

        // Check if upgrade is needed
        boolean needsUpgrade = false;
        if (old.has("orbit") ||
            (old.path("behaviour").path("kind").asText().equals("repeat")) ||
            (!old.path("actions").isArray() || !hasThrowAction(old.path("actions")))) {
            needsUpgrade = true;
        }

        if (!needsUpgrade) {
            return;
        }

        ObjectNode target = loadSeed("orbiter");
        ObjectNode s = old.deepCopy();
        s.remove("orbit");

        // Copy "actions" and "behaviour" from the target
        s.set("actions", target.get("actions").deepCopy());
        s.set("behaviour", target.get("behaviour").deepCopy());

        // Keep existing speed if present, otherwise set default
        if (!old.path("speed").isNumber()) {
            s.put("speed", 0.5);
        }

        // If no position in old state, add from target
        if (!old.has("position")) {
            s.set("position", target.get("position").deepCopy());
        }

        // Handle orbit parameters if needed
        ObjectNode orbitParams = null;

        // Check for existing orbit parameters in order of preference
        if (old.has("orbit")) {
            JsonNode orbit = old.path("orbit");
            ObjectNode center = MAPPER.createObjectNode();
            if (orbit.path("center").isObject()) {
                center = (ObjectNode) orbit.path("center").deepCopy();
            } else {
                center.put("x", 5.0).put("y", 4.0);
            }
            double radius = orbit.path("radius").asDouble(3.0);
            double arc = 0.5;

            orbitParams = MAPPER.createObjectNode();
            orbitParams.set("center", center);
            orbitParams.put("radius", radius);
            orbitParams.put("arc", arc);
        } else if (old.path("behaviour").path("kind").asText().equals("repeat")) {
            // Use the action params from repeat behaviour
            orbitParams = (ObjectNode) old.path("behaviour").path("action").path("params").deepCopy();
        } else {
            // Look for orbit-step in choices
            JsonNode choices = old.path("behaviour").path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode action = choice.path("action");
                    if ("orbit-step".equals(action.path("type").asText())) {
                        orbitParams = (ObjectNode) action.path("params").deepCopy();
                        break;
                    }
                }
            }
        }

        // If we found orbit parameters, set them in the correct place
        if (orbitParams != null && s.has("behaviour")) {
            JsonNode behaviour = s.get("behaviour");
            if (behaviour.has("choices")) {
                JsonNode choices = behaviour.get("choices");
                if (choices.isArray()) {
                    for (JsonNode choice : choices) {
                        JsonNode action = choice.path("action");
                        if ("orbit-step".equals(action.path("type").asText())) {
                            ((ObjectNode) action).set("params", orbitParams.deepCopy());
                            break;
                        }
                    }
                }
            }
        }

        store.updateState(WorldIds.ORBITER, s);
        log.info("upgraded {} to the action system", WorldIds.ORBITER);
    }

    private boolean hasThrowAction(JsonNode actions) {
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                if ("throw".equals(action.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void create(String id, ResourceType type, String parent, String stateJson) {
        try {
            JsonNode state = MAPPER.readTree(stateJson);
            store.create(new Resource(id, type, 1, List.of(), (ObjectNode) state), parent);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void create(String id, ResourceType type, String parent, ObjectNode state) {
        store.create(new Resource(id, type, 1, List.of(), state), parent);
    }
}
