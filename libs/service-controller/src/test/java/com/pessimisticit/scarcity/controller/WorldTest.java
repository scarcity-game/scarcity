package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.WorldJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WorldTest {

    private final StubWorld stubWorld;

    WorldTest() throws IOException {
        this.stubWorld = new StubWorld();
    }

    @AfterEach
    void stop() {
        stubWorld.close();
    }

    @Test
    void entitiesReturnsAllEntitiesAcrossPages() {
        String area = StubWorld.AREA;
        List<String> entityIds = new ArrayList<>();
        
        for (int i = 0; i < 209; i++) {
            String id = "entity:00000000-0000-4000-8000-" + String.format("%012d", 100000000000L + i);
            Entity mouse = Prototypes.get("mouse").withPosition(new Position(5.0, 5.0))
                    .withActions(List.of("step", "wait"))
                    .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("tester"));
            stubWorld.entity(id, 1, WorldJson.toJson(mouse).replace("{\"resourceKind\":\"entity\",", "{"));
            entityIds.add(id);
        }
        
        stubWorld.children(area, entityIds);
        
        ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
        World world = new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, new TestClock()));
        
        List<Entity> entities = world.entities(area);
        
        assertThat(entities).hasSize(209);
        assertThat(stubWorld.worldRequests()).hasSize(2);
        
        List<String> paths = stubWorld.worldRequests().stream()
                .map(r -> r.path())
                .toList();
        assertThat(paths).containsExactly(
                "/api/world/resources/area:00000000-0000-4000-8000-000000000002/children",
                "/api/world/resources/area:00000000-0000-4000-8000-000000000002/children"
        );
    }

    @Test
    void entitiesWithExactPageSizeReturnsSinglePage() {
        String area = StubWorld.AREA;
        List<String> entityIds = new ArrayList<>();
        
        for (int i = 0; i < 200; i++) {
            String id = "entity:00000000-0000-4000-8000-" + String.format("%012d", 100000000000L + i);
            Entity mouse = Prototypes.get("mouse").withPosition(new Position(5.0, 5.0))
                    .withActions(List.of("step", "wait"))
                    .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("tester"));
            stubWorld.entity(id, 1, WorldJson.toJson(mouse).replace("{\"resourceKind\":\"entity\",", "{"));
            entityIds.add(id);
        }
        
        stubWorld.children(area, entityIds, 200);
        
        ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
        World world = new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, new TestClock()));
        
        List<Entity> entities = world.entities(area);
        
        assertThat(entities).hasSize(200);
        assertThat(stubWorld.worldRequests()).hasSize(1);
    }

    @Test
    void entitiesWithFewerThanPageSizeReturnsSinglePage() {
        String area = StubWorld.AREA;
        List<String> entityIds = new ArrayList<>();
        
        for (int i = 0; i < 50; i++) {
            String id = "entity:00000000-0000-4000-8000-" + String.format("%012d", 100000000000L + i);
            Entity mouse = Prototypes.get("mouse").withPosition(new Position(5.0, 5.0))
                    .withActions(List.of("step", "wait"))
                    .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("tester"));
            stubWorld.entity(id, 1, WorldJson.toJson(mouse).replace("{\"resourceKind\":\"entity\",", "{"));
            entityIds.add(id);
        }
        
        stubWorld.children(area, entityIds, 200);
        
        ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
        World world = new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, new TestClock()));
        
        List<Entity> entities = world.entities(area);
        
        assertThat(entities).hasSize(50);
        assertThat(stubWorld.worldRequests()).hasSize(1);
    }

    @Test
    void entitiesReturnsEntitiesInServerOrder() {
        String area = StubWorld.AREA;
        List<String> entityIds = new ArrayList<>();
        
        for (int i = 0; i < 250; i++) {
            String id = "entity:00000000-0000-4000-8000-" + String.format("%012d", 100000000000L + i);
            Entity mouse = Prototypes.get("mouse").withPosition(new Position(5.0, 5.0))
                    .withActions(List.of("step", "wait"))
                    .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("tester"));
            stubWorld.entity(id, 1, WorldJson.toJson(mouse).replace("{\"resourceKind\":\"entity\",", "{"));
            entityIds.add(id);
        }
        
        stubWorld.children(area, entityIds);
        
        ControllerProperties props = Fixtures.props(stubWorld.url(), "ws://unused", Fixtures.KEY);
        World world = new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, new TestClock()));
        
        List<Entity> entities = world.entities(area);
        
        assertThat(entities).hasSize(250);
        assertThat(stubWorld.worldRequests()).hasSize(2);
        
        List<String> retrievedIds = entities.stream().map(e -> e.id()).toList();
        assertThat(retrievedIds).isEqualTo(entityIds);
    }
}
