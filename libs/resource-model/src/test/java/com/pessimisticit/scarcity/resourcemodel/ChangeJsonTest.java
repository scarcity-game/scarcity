package com.pessimisticit.scarcity.resourcemodel;

import static com.pessimisticit.scarcity.resourcemodel.Changes.change;
import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeJsonTest {

    private List<String> keys(String json, int index) throws Exception {
        JsonNode change = Json.MAPPER.readTree(json).get("changes").get(index);
        List<String> names = new ArrayList<>();
        change.propertyNames().forEach(names::add);
        return names;
    }

    private Resource stored(ResourceKind kind, long version) {
        return AbstractDbTest.resource(kind, 2.5).withVersion(version).withChildren(List.of());
    }

    @Test
    void aResourceIsItsOwnChangeAsFlatJson() throws Exception {
        Resource entity = stored(ResourceKind.ENTITY, 3);
        ChangeBatch batch = new ChangeBatch("area:x", List.of(ChangeBatch.Change.of(entity)), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(keys(json, 0)).startsWith("resourceKind").contains("id", "version", "children", "position");
        assertThat(keys(json, 0)).doesNotContain("state", "patch", "resourceType");
        assertThat(Json.MAPPER.readValue(json, ChangeBatch.class)).isEqualTo(batch);
        assertThat(batch.changes().get(0).resource()).contains(entity);
        assertThat(batch.changes().get(0).version()).isEqualTo(3);
        assertThat(batch.changes().get(0).deleted()).isFalse();
    }

    @Test
    void areaChangeCarriesItsChildren() throws Exception {
        ChangeBatch.Change change = change("area:x", ResourceKind.AREA, 5, null, List.of("entity:a", "entity:b"));
        String json = Json.MAPPER.writeValueAsString(new ChangeBatch("area:x", List.of(change), null));
        assertThat(Json.MAPPER.readTree(json).get("changes").get(0).get("children")).hasSize(2);
    }

    @Test
    void deletedChangeIsATombstone() throws Exception {
        ChangeBatch batch = new ChangeBatch("area:x",
                List.of(ChangeBatch.Change.deleted("entity:d", ResourceKind.ENTITY)), null);
        String json = Json.MAPPER.writeValueAsString(batch);
        assertThat(json).isEqualTo("{\"area\":\"area:x\",\"changes\":[{\"resourceKind\":\"entity\","
                + "\"id\":\"entity:d\",\"deleted\":true}]}");
        ChangeBatch readBack = Json.MAPPER.readValue(json, ChangeBatch.class);
        assertThat(readBack).isEqualTo(batch);
        assertThat(readBack.changes().get(0).deleted()).isTrue();
        assertThat(readBack.changes().get(0).version()).isZero();
        assertThat(readBack.changes().get(0).resource()).isEmpty();
    }

    @Test
    void stubKeepsOnlyKindIdAndVersion() throws Exception {
        ChangeBatch.Change stub = ChangeBatch.Change.of(stored(ResourceKind.AREA, 9)).stub();
        assertThat(stub.json().size()).isEqualTo(3);
        assertThat(stub.version()).isEqualTo(9);
        assertThat(stub.resourceKind()).isEqualTo(ResourceKind.AREA);
        assertThat(stub.resource()).isEmpty();
    }

    @Test
    void mixedBatchRoundTrips() throws Exception {
        String id = Ids.newId(ResourceKind.ENTITY);
        List<ChangeBatch.Change> changes = List.of(
                ChangeBatch.Change.of(stored(ResourceKind.ENTITY, 3)),
                change("area:x", ResourceKind.AREA, 5, null, List.of(id)),
                ChangeBatch.Change.deleted(id, ResourceKind.ENTITY));
        ChangeBatch batch = new ChangeBatch("area:x", changes, null);
        assertThat(Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(batch), ChangeBatch.class)).isEqualTo(batch);
    }
}
