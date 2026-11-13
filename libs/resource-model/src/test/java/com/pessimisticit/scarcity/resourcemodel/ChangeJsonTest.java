package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ChangeJsonTest {

    private List<String> keys(String json, int index) throws Exception {
        JsonNode change = Json.MAPPER.readTree(json).get("changes").get(index);
        List<String> names = new java.util.ArrayList<>();
        change.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void plainChange() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.changed("entity:a", ResourceType.ENTITY, 3,
                Json.MAPPER.createObjectNode().put("hp", 4.5));
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).containsExactly("id", "type", "version", "patch");

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
    }

    @Test
    void changeWithChildren() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.changed("area:x", ResourceType.AREA, 5, null,
                List.of("entity:a", "entity:b"));
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).containsExactly("id", "type", "version", "children");

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
    }

    @Test
    void createdResource() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.changed("entity:n", ResourceType.ENTITY, 1,
                Json.MAPPER.createObjectNode().put("hp", 1.0), List.of());
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).containsExactly("id", "type", "version", "patch", "children");

        // Verify children is an empty array
        JsonNode tree = Json.MAPPER.readTree(json);
        JsonNode changesArray = tree.get("changes");
        JsonNode firstChange = changesArray.get(0);
        assertThat(firstChange.get("children").isArray()).isTrue();
        assertThat(firstChange.get("children").size()).isEqualTo(0);

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
    }

    @Test
    void deletedChange() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.deleted("entity:d", ResourceType.ENTITY);
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).containsExactly("id", "type", "deleted");

        // Verify exact JSON string
        String expectedJson = "{\"area\":\"area:x\",\"changes\":[{\"id\":\"entity:d\",\"type\":\"entity\",\"deleted\":true}]}";
        assertThat(json).isEqualTo(expectedJson);

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
        assertThat(readBack.changes().get(0).deleted()).isTrue();
        assertThat(readBack.changes().get(0).version()).isEqualTo(0);
        assertThat(readBack.changes().get(0).patch()).isNull();
        assertThat(readBack.changes().get(0).children()).isNull();
    }

    @Test
    void mixedBatch() throws Exception {
        List<ChangeBatch.Change> changes = List.of(
                ChangeBatch.Change.changed("entity:a", ResourceType.ENTITY, 3,
                        Json.MAPPER.createObjectNode().put("hp", 4.5)),
                ChangeBatch.Change.changed("area:x", ResourceType.AREA, 5, null,
                        List.of("entity:a", "entity:b")),
                ChangeBatch.Change.changed("entity:n", ResourceType.ENTITY, 1,
                        Json.MAPPER.createObjectNode().put("hp", 1.0), List.of()),
                ChangeBatch.Change.deleted("entity:d", ResourceType.ENTITY)
        );
        ChangeBatch batch = new ChangeBatch("area:x", changes, null);

        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).containsExactly("id", "type", "version", "patch");
        assertThat(keys(json, 1)).containsExactly("id", "type", "version", "children");
        assertThat(keys(json, 2)).containsExactly("id", "type", "version", "patch", "children");
        assertThat(keys(json, 3)).containsExactly("id", "type", "deleted");

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
    }

    @Test
    void oldShapeParses() throws Exception {
        String json = "{\"area\":\"area:x\",\"changes\":[{\"id\":\"entity:a\",\"type\":\"entity\",\"version\":3,\"patch\":{\"hp\":4.5}}]}";
        ChangeBatch batch = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(batch.changes().get(0).children()).isNull();
        assertThat(batch.changes().get(0).deleted()).isFalse();
        assertThat(batch.changes().get(0).version()).isEqualTo(3);
    }

    @Test
    void fourArgConstructorStillWorks() {
        ChangeBatch.Change original = new ChangeBatch.Change("entity:a", ResourceType.ENTITY, 3, null);
        ChangeBatch.Change changed = ChangeBatch.Change.changed("entity:a", ResourceType.ENTITY, 3, null);
        assertThat(original).isEqualTo(changed);
    }
}