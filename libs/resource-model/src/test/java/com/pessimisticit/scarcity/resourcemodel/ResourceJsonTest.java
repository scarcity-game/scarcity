package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ResourceJsonTest {

    @Test
    void resourceHasExactlyFiveKeysInOrderAndLowerCaseType() throws Exception {
        ObjectNode state = Json.MAPPER.createObjectNode().put("hp", 4.5);
        Resource r = new Resource("area:" + "7f3c1c0e-0000-4000-8000-000000000001", ResourceType.AREA, 41,
                List.of("entity:7f3c1c0e-0000-4000-8000-000000000002"), state);
        JsonNode n = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(r));
        List<String> keys = new ArrayList<>();
        n.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("id", "type", "version", "children", "state");
        assertThat(n.get("type").asText()).isEqualTo("area");
        assertThat(n.get("version").asLong()).isEqualTo(41);
        assertThat(Json.MAPPER.writeValueAsString(r)).startsWith("{\"id\":\"area:");
    }

    @Test
    void allTypesAreLowerCase() throws Exception {
        for (ResourceType t : ResourceType.values()) {
            assertThat(Json.MAPPER.writeValueAsString(t)).isEqualTo("\"" + t.name().toLowerCase() + "\"");
            assertThat(Json.MAPPER.readValue("\"" + t.wire() + "\"", ResourceType.class)).isEqualTo(t);
        }
    }

    @Test
    void nullPatchAndTraceparentAreOmitted() throws Exception {
        ChangeBatch b = new ChangeBatch("area:x",
                List.of(new ChangeBatch.Change("entity:y", ResourceType.ENTITY, 3, null)), null);
        assertThat(Json.MAPPER.writeValueAsString(b))
                .isEqualTo("{\"area\":\"area:x\",\"changes\":[{\"id\":\"entity:y\",\"type\":\"entity\",\"version\":3}]}");
        ChangeBatch full = new ChangeBatch("area:x", List.of(new ChangeBatch.Change("entity:y", ResourceType.ENTITY,
                3, Json.MAPPER.createObjectNode().put("x", 1.5))), "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
        String json = Json.MAPPER.writeValueAsString(full);
        assertThat(json).contains("\"patch\":{\"x\":1.5}").contains("\"traceparent\":\"00-");
        assertThat(Json.MAPPER.readValue(json, ChangeBatch.class)).isEqualTo(full);
    }

    @Test
    void idsMintParseAndMatch() {
        for (ResourceType t : ResourceType.values()) {
            String id = ResourceIds.newId(t);
            assertThat(id).startsWith(t.wire() + ":");
            assertThat(ResourceIds.typeOf(id)).isEqualTo(t);
            assertThat(ResourceIds.matches(t, id)).isTrue();
        }
        String area = ResourceIds.newId(ResourceType.AREA);
        assertThat(ResourceIds.matches(ResourceType.ENTITY, area)).isFalse();
        assertThat(ResourceIds.isValid("area:not-a-uuid")).isFalse();
        assertThat(ResourceIds.isValid("nope")).isFalse();
        assertThat(ResourceIds.isValid("zone:" + java.util.UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> ResourceIds.typeOf("")).isInstanceOf(IllegalArgumentException.class);
    }
}
