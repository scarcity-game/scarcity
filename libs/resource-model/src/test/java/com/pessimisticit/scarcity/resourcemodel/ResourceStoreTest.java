package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ResourceStoreTest extends AbstractDbTest {

    @Test
    void versionRuleAndChildren() {
        String game = create(ResourceType.GAME, null, state("n", unique()));
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(1);

        String area = create(ResourceType.AREA, game, state("w", 10.5));
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(2);
        assertThat(store.get(game).orElseThrow().children()).containsExactly(area);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(1);

        String ent = create(ResourceType.ENTITY, area, state("kind", "mobile"));
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(2);
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(2);

        WriteResult w = tx.execute(s -> store.updateState(ent, state("x", 1.25)));
        assertThat(w.version()).isEqualTo(2);
        assertThat(w.parentVersion()).isNull();
        assertThat(tx.execute(s -> store.updateState(ent, state("x", 2.5))).version()).isEqualTo(3);
        assertThat(store.get(ent).orElseThrow().state().get("x").asDouble()).isEqualTo(2.5);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(2);

        WriteResult d = tx.execute(s -> store.delete(ent));
        assertThat(d.parentVersion()).isEqualTo(3);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(3);
        assertThat(store.get(area).orElseThrow().children()).isEmpty();
        assertThat(store.get(ent)).isEmpty();
    }

    @Test
    void areaIdIsDerivedFromParent() {
        String game = create(ResourceType.GAME, null, state("n", 1));
        String area = create(ResourceType.AREA, game, state("n", 1));
        String ent = create(ResourceType.ENTITY, area, state("n", 1));
        String item = create(ResourceType.ITEM, ent, state("n", 1));
        assertThat(store.areaOf(game)).isEmpty();
        assertThat(store.areaOf(area)).contains(area);
        assertThat(store.areaOf(ent)).contains(area);
        assertThat(store.areaOf(item)).contains(area);
        WriteResult w = tx.execute(s -> store.updateState(item, state("n", 2)));
        assertThat(w.areaId()).isEqualTo(area);
        assertThat(w.parentId()).isEqualTo(ent);
    }

    @Test
    void rolledBackTransactionLeavesVersionAndStateUntouched() {
        String game = create(ResourceType.GAME, null, state("n", 1));
        String area = create(ResourceType.AREA, game, state("v", "before"));
        String ent = create(ResourceType.ENTITY, area, state("v", "before"));
        long areaV = store.get(area).orElseThrow().version();

        tx.executeWithoutResult(s -> {
            store.updateState(ent, state("v", "after"));
            store.create(Resource.newResource(ResourceType.ENTITY, state("v", 1)), area);
            store.delete(ent);
            s.setRollbackOnly();
        });

        assertThat(store.get(ent).orElseThrow().version()).isEqualTo(1);
        assertThat(store.get(ent).orElseThrow().state().get("v").asText()).isEqualTo("before");
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(areaV);
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);
    }

    @Test
    void errors() {
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> store.updateState(ResourceIds.newId(ResourceType.ENTITY), state("a", 1))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> store.create(Resource.newResource(ResourceType.ENTITY, state("a", 1)),
                        ResourceIds.newId(ResourceType.AREA))))
                .isInstanceOf(ResourceNotFoundException.class);
        String game = create(ResourceType.GAME, null, state("n", 1));
        String area = create(ResourceType.AREA, game, state("n", 1));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.delete(game)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(area).isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.create(
                new Resource(ResourceIds.newId(ResourceType.AREA), ResourceType.ENTITY, 1, null, state("a", 1)), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listFilterAndPaging() {
        String tag = unique();
        for (int i = 0; i < 5; i++) {
            create(ResourceType.GAME, null, state("tag", tag).put("n", i));
        }
        create(ResourceType.GAME, null, state("tag", "other-" + tag));
        Page<Resource> p0 = store.list(ResourceType.GAME, Map.of("tag", tag), 0, 2);
        Page<Resource> p2 = store.list(ResourceType.GAME, Map.of("tag", tag), 2, 2);
        assertThat(p0.total()).isEqualTo(5);
        assertThat(p0.items()).hasSize(2);
        assertThat(p2.items()).hasSize(1);
        assertThat(p0.items().get(0).state().get("n").asInt()).isEqualTo(0);
        assertThat(p2.items().get(0).state().get("n").asInt()).isEqualTo(4);
        assertThat(store.list(ResourceType.GAME, Map.of("tag", tag, "n", "3"), 0, 10).total()).isEqualTo(1);
        assertThat(store.list(ResourceType.AREA, Map.of("tag", tag), 0, 10).total()).isZero();
    }

    @Test
    void filterKeysAreValidatedAndNeverInjected() {
        assertThatThrownBy(() -> store.list(ResourceType.GAME, Map.of("a'; DROP TABLE resources;--", "x"), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // value is a bound parameter: a hostile value just matches nothing
        assertThat(store.list(ResourceType.GAME, Map.of("tag", "x' OR '1'='1"), 0, 10).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources", Long.class)).isNotNull();
    }

    @Test
    void childrenFilteredByType() {
        String game = create(ResourceType.GAME, null, state("n", 1));
        String a1 = create(ResourceType.AREA, game, state("n", 1));
        String a2 = create(ResourceType.AREA, game, state("n", 2));
        Page<Resource> all = store.children(game, null, 0, 10);
        assertThat(all.items()).extracting(Resource::id).containsExactly(a1, a2);
        assertThat(store.children(game, ResourceType.ENTITY, 0, 10).total()).isZero();
    }
}
