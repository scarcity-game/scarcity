package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Entity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** {@link World#entities} reads every page of an area's entities (#345). */
class WorldTest {

    @Test
    void entitiesReadsEveryPage() throws IOException {
        try (StubWorld stubWorld = new StubWorld()) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 209; i++) {
                String id = "entity:00000000-0000-4000-8000-%012d".formatted(1000 + i);
                stubWorld.entity(id, 1, StubWorld.PILLAR_STATE);
                ids.add(id);
            }
            stubWorld.children(StubWorld.AREA, ids);
            ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
            World world = new World(RestClient.builder(), props,
                    new ServiceToken(RestClient.builder(), props, new TestClock()));

            List<Entity> entities = world.entities(StubWorld.AREA);

            assertThat(entities).hasSize(209);
            assertThat(entities.get(208).id()).isEqualTo(ids.get(208));
            assertThat(entities).extracting(Entity::id).containsExactlyElementsOf(ids);
            assertThat(stubWorld.worldRequests()).hasSize(2);
        }
    }
}
