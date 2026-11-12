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
                    return false;
                }
                create(WorldIds.GAME, ResourceType.GAME, null,
                        "{\"name\":\"Test area\"}");
                create(WorldIds.AREA, ResourceType.AREA, WorldIds.GAME,
                        "{\"name\":\"Test room\",\"width\":10.0,\"height\":8.0}");
                create(WorldIds.PILLAR, ResourceType.ENTITY, WorldIds.AREA,
                        "{\"kind\":\"fixed\",\"name\":\"pillar\",\"position\":{\"x\":5.0,\"y\":4.0}}");
                create(WorldIds.ORBITER, ResourceType.ENTITY, WorldIds.AREA,
                        "{\"kind\":\"mobile\",\"name\":\"orbiter\",\"position\":{\"x\":8.0,\"y\":4.0},"
                                + "\"orbit\":{\"center\":{\"x\":5.0,\"y\":4.0},\"radius\":3.0,\"period\":60.0}}");
                log.info("seeded test game {}", WorldIds.GAME);
                return true;
            }));
        } catch (DuplicateKeyException e) {
            // Another replica seeded between our exists() and insert; its transaction won.
            log.info("test game was seeded concurrently; nothing to do");
            return false;
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
