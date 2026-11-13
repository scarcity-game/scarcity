package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class LogSeqJsonTest {

    @Test
    void logSeqSerialisedBetweenChangesAndTraceparent() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.changed("entity:a", ResourceType.ENTITY, 3, null);
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), 17L, "tp");

        String json = Json.MAPPER.writeValueAsString(batch);

        // Assert top-level field names in order
        JsonNode tree = Json.MAPPER.readTree(json);
        List<String> fieldNames = new java.util.ArrayList<>();
        tree.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly("area", "changes", "logSeq", "traceparent");

        // Assert logSeq value is 17
        assertThat(tree.get("logSeq").asLong()).isEqualTo(17L);

        // Read back and assert equals original
        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
    }

    @Test
    void nullLogSeqIsOmitted() throws Exception {
        ChangeBatch.Change change = ChangeBatch.Change.changed("entity:a", ResourceType.ENTITY, 3, null);
        ChangeBatch batch = new ChangeBatch("area:x", List.of(change), "tp");

        String json = Json.MAPPER.writeValueAsString(batch);

        // JSON tree should not have 'logSeq' field
        JsonNode tree = Json.MAPPER.readTree(json);
        assertThat(tree.has("logSeq")).isFalse();

        // Read back and assert logSeq is null
        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack.logSeq()).isNull();
    }

    @Test
    void oldJsonWithoutLogSeqParses() throws Exception {
        String json = "{\"area\":\"area:x\",\"changes\":[{\"id\":\"entity:a\",\"type\":\"entity\",\"version\":3}],\"traceparent\":\"tp\"}";

        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack.logSeq()).isNull();
        assertThat(readBack.traceparent()).isEqualTo("tp");
    }
}