package com.pessimisticit.scarcity.resourcemodel;

import static com.pessimisticit.scarcity.resourcemodel.Changes.change;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import org.junit.jupiter.api.Test;

class ResourceJsonTest {

    @Test
    void nullTraceparentIsOmittedAndPresentOneRoundTrips() throws Exception {
        ChangeBatch b = new ChangeBatch("area:x", java.util.List.of(change("entity:y", 3, null)), null);
        assertThat(Json.MAPPER.writeValueAsString(b)).isEqualTo("{\"area\":\"area:x\",\"changes\":["
                + "{\"resourceKind\":\"entity\",\"id\":\"entity:y\",\"version\":3}]}");
        ChangeBatch full = new ChangeBatch("area:x",
                java.util.List.of(change("entity:y", 3, Json.MAPPER.createObjectNode().put("x", 1.5))),
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
        String json = Json.MAPPER.writeValueAsString(full);
        assertThat(json).contains("\"x\":1.5").contains("\"traceparent\":\"00-");
        assertThat(Json.MAPPER.readValue(json, ChangeBatch.class)).isEqualTo(full);
    }

    @Test
    void idsMintParseAndMatch() {
        for (ResourceKind kind : ResourceKind.values()) {
            String id = Ids.newId(kind);
            assertThat(id).startsWith(kind.prefix() + ":");
            assertThat(Ids.kindOf(id)).isEqualTo(kind);
            assertThat(Ids.isValid(id)).isTrue();
        }
        assertThat(Ids.isValid("area:not-a-uuid")).isFalse();
        assertThat(Ids.isValid("nope")).isFalse();
        assertThat(Ids.isValid("zone:" + java.util.UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> Ids.kindOf("")).isInstanceOf(IllegalArgumentException.class);
    }
}
