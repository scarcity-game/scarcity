package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change.Kind;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The deltas of docs/resources.md, "Change notifications": what each kind of change and a batch look like. */
class ChangeJsonTest {

    private static Entity stored(double hp, long version) {
        return ((Entity) AbstractDbTest.resource(ResourceKind.ENTITY, hp)).withVersion(version)
                .withChildren(List.of());
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void anUpdateSetsOnlyWhatChangedWhole() {
        Entity before = stored(2.5, 3);
        Entity after = before.withHp(1.5).withPosition(new Position(4.0, 2.0)).withVersion(4);

        Change update = Change.update(before, after, null, null);

        assertThat(update.kind()).isEqualTo(Kind.UPDATE);
        assertThat(keys(update.json())).containsExactly("resourceKind", "id", "version", "set");
        assertThat(update.version()).isEqualTo(4);
        assertThat(keys(update.set())).containsExactlyInAnyOrder("hp", "position");
        assertThat(update.set().get("position").get("x").asDouble()).isEqualTo(4.0);
    }

    @Test
    void aKeyThatIsGoneOrNullIsUnset() {
        Entity before = stored(2.5, 3).withController(new com.pessimisticit.scarcity.world.model.Controller.Service(
                "microbe"));
        Entity after = before.withController(null).withVersion(4);

        Change update = Change.update(before, after, null, null);

        assertThat(update.unset()).containsExactly("controller");
        assertThat(update.set().isEmpty()).isTrue();
    }

    @Test
    void sameNumberWrittenDifferentlyIsNoChange() {
        ObjectNode a = Json.MAPPER.createObjectNode().put("time", 3);
        ObjectNode b = Json.MAPPER.createObjectNode().put("time", 3.0);
        assertThat(a.get("time").equals(Change::compareNodes, b.get("time"))).isTrue();
    }

    @Test
    void anUpdateWithNothingInItCarriesAnEmptySetSoItIsNoStub() {
        Entity before = stored(2.5, 3);

        Change update = Change.update(before, before.withVersion(4), List.of(), List.of());

        assertThat(Json.MAPPER.writeValueAsString(update)).endsWith("\"version\":4,\"set\":{}}");
        assertThat(update.kind()).isEqualTo(Kind.UPDATE);
        assertThat(update.stub().kind()).isEqualTo(Kind.STUB);
    }

    @Test
    void appliedToTheOldResourceAnUpdateGivesTheNewOne() {
        Entity controlled = stored(2.5, 3)
                .withController(new com.pessimisticit.scarcity.world.model.Controller.Service("microbe"));
        Entity before = controlled.withChildren(List.of("item:a", "item:b"));
        Entity changed = before.withHp(0.5).withController(null);
        Entity after = changed.<Entity>withVersion(4).withChildren(List.of("item:b", "item:c"));

        Change update = Change.update(before, after, List.of("item:c"), List.of("item:a"));
        ObjectNode applied = update.applyTo((ObjectNode) WorldJson.toTree(before));

        assertThat(applied.equals(Change::compareNodes, WorldJson.toTree(after))).isTrue();
    }

    @Test
    void anUpdateIsOnlyOfOneResource() {
        assertThatThrownBy(() -> Change.update(stored(1, 1), stored(1, 2), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createdDeletedAndStubCarryNoState() {
        Change created = Change.created("entity:c", ResourceKind.ENTITY, 1);
        Change deleted = Change.deleted("entity:d", ResourceKind.ENTITY);
        Change stub = Change.update(ResourceKind.AREA, "area:s", 9,
                Json.MAPPER.createObjectNode().put("time", 1.0), null, null, null).stub();

        assertThat(Json.MAPPER.writeValueAsString(created))
                .isEqualTo("{\"resourceKind\":\"entity\",\"id\":\"entity:c\",\"version\":1,\"created\":true}");
        assertThat(Json.MAPPER.writeValueAsString(deleted))
                .isEqualTo("{\"resourceKind\":\"entity\",\"id\":\"entity:d\",\"deleted\":true}");
        assertThat(Json.MAPPER.writeValueAsString(stub))
                .isEqualTo("{\"resourceKind\":\"area\",\"id\":\"area:s\",\"version\":9}");
        assertThat(List.of(created, deleted, stub)).extracting(Change::kind)
                .containsExactly(Kind.CREATED, Kind.DELETED, Kind.STUB);
        assertThat(deleted.version()).isZero();
        assertThat(created.stub()).isEqualTo(created);
        assertThat(deleted.stub()).isEqualTo(deleted);
    }

    @Test
    void batchKeysComeInContractOrderAndNullsAreLeftOut() throws Exception {
        ChangeBatch batch = ChangeBatch.of("area:x", List.of(Change.deleted("entity:d", ResourceKind.ENTITY)),
                16L, 17L, "tp");

        JsonNode tree = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(batch));

        assertThat(keys(tree)).containsExactly("area", "changes", "fromLogSeq", "logSeq", "traceparent");
        assertThat(keys(Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(batch.part(batch.changes(), 1, 2)))))
                .containsExactly("area", "changes", "fromLogSeq", "logSeq", "part", "parts", "traceparent");
        assertThat(Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(batch), ChangeBatch.class)).isEqualTo(batch);
    }

    @Test
    void aReloadHasNoChanges() throws Exception {
        ChangeBatch reload = ChangeBatch.reload("area:x", 5L, null);

        assertThat(Json.MAPPER.writeValueAsString(reload))
                .isEqualTo("{\"area\":\"area:x\",\"reload\":true,\"logSeq\":5}");
        assertThat(Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(reload), ChangeBatch.class).reloading())
                .isTrue();
        assertThatThrownBy(() -> new ChangeBatch("area:x", true,
                List.of(Change.deleted("entity:d", ResourceKind.ENTITY)), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
