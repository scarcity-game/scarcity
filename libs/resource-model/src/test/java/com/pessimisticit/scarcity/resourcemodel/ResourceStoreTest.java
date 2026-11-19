package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import org.junit.jupiter.api.Test;

class ResourceStoreTest extends AbstractDbTest {

    @Test
    void listScopedToAGame() {
        String g1 = create(resource(ResourceKind.GAME, 1), null);
        String g2 = create(resource(ResourceKind.GAME, 2), null);
        String a1 = create(resource(ResourceKind.AREA, 1), g1);
        String a2 = create(resource(ResourceKind.AREA, 2), g1);
        String b1 = create(resource(ResourceKind.AREA, 3), g2);
        String e1 = create(resource(ResourceKind.ENTITY, 1), a1);
        String e2 = create(resource(ResourceKind.ENTITY, 2), a2);
        String e3 = create(resource(ResourceKind.ENTITY, 3), b1);
        assertThat(store.list(ResourceKind.ENTITY, g1, Map.of(), 0, 50).items()).extracting(Resource::id)
                .containsExactly(e1, e2);
        assertThat(store.list(ResourceKind.ENTITY, g2, Map.of(), 0, 50).items()).extracting(Resource::id)
                .containsExactly(e3);
        assertThat(store.list(ResourceKind.AREA, g1, Map.of(), 0, 50).items()).extracting(Resource::id)
                .containsExactly(a1, a2);
        assertThat(store.list(ResourceKind.GAME, g2, Map.of(), 0, 50).items()).extracting(Resource::id)
                .containsExactly(g2);
        // paging counts only the game's resources
        Page<Resource> second = store.list(ResourceKind.ENTITY, g1, Map.of(), 1, 1);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(Resource::id).containsExactly(e2);
        // the game is optional and an unknown game is empty
        assertThat(store.list(ResourceKind.ENTITY, null, Map.of(), 0, 200).total()).isGreaterThanOrEqualTo(3);
        assertThat(store.list(ResourceKind.ENTITY, Ids.newId(ResourceKind.GAME), Map.of(), 0, 50).total()).isZero();
        assertThatThrownBy(() -> store.list(ResourceKind.ENTITY, a1, Map.of(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list(ResourceKind.ENTITY, "nope", Map.of(), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionRuleAndChildren() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(1);

        String area = create(resource(ResourceKind.AREA, 10.5), game);
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(2);
        assertThat(store.get(game).orElseThrow().children()).containsExactly(area);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(1);

        String ent = create(resource(ResourceKind.ENTITY, "mobile"), area);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(2);
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);
        assertThat(store.get(game).orElseThrow().version()).isEqualTo(2);

        WriteResult w = tx.execute(s -> store.update(resource(Ids.kindOf(ent), 1.25).withId(ent)));
        assertThat(w.version()).isEqualTo(2);
        assertThat(w.parentVersion()).isNull();
        assertThat(tx.execute(s -> store.update(resource(Ids.kindOf(ent), 2.5).withId(ent))).version()).isEqualTo(3);
        assertThat(value(store.get(ent).orElseThrow())).isEqualTo(2.5);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(2);

        WriteResult d = tx.execute(s -> store.delete(ent));
        assertThat(d.parentVersion()).isEqualTo(3);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(3);
        assertThat(store.get(area).orElseThrow().children()).isEmpty();
        assertThat(store.get(ent)).isEmpty();
    }

    @Test
    void areaIdIsDerivedFromParent() {
        String game = create(resource(ResourceKind.GAME, 1), null);
        String area = create(resource(ResourceKind.AREA, 1), game);
        String ent = create(resource(ResourceKind.ENTITY, 1), area);
        assertThat(store.areaOf(game)).isEmpty();
        assertThat(store.areaOf(area)).contains(area);
        assertThat(store.areaOf(ent)).contains(area);
        assertThat(store.parentOf(game)).isEmpty();
        assertThat(store.parentOf(area)).contains(game);
        assertThat(store.parentOf(ent)).contains(area);
        WriteResult w = tx.execute(s -> store.update(resource(ResourceKind.ENTITY, 2).withId(ent)));
        assertThat(w.areaId()).isEqualTo(area);
        assertThat(w.parentId()).isEqualTo(area);
    }

    @Test
    void rolledBackTransactionLeavesVersionAndStateUntouched() {
        String game = create(resource(ResourceKind.GAME, 1), null);
        String area = create(resource(ResourceKind.AREA, "before"), game);
        String ent = create(resource(ResourceKind.ENTITY, "before"), area);
        long areaV = store.get(area).orElseThrow().version();

        tx.executeWithoutResult(s -> {
            store.update(resource(Ids.kindOf(ent), "after").withId(ent));
            store.create(resource(ResourceKind.ENTITY, 1), area);
            store.delete(ent);
            s.setRollbackOnly();
        });

        assertThat(store.get(ent).orElseThrow().version()).isEqualTo(1);
        assertThat(value(store.get(ent).orElseThrow())).isEqualTo("before");
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(areaV);
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);
    }

    @Test
    void errors() {
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> store.update(resource(Ids.kindOf(Ids.newId(ResourceKind.ENTITY)), 1).withId(Ids.newId(ResourceKind.ENTITY)))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> store.create(resource(ResourceKind.ENTITY, 1),
                        Ids.newId(ResourceKind.AREA))))
                .isInstanceOf(ResourceNotFoundException.class);
        String game = create(resource(ResourceKind.GAME, 1), null);
        String area = create(resource(ResourceKind.AREA, 1), game);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.delete(game)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(area).isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.create(
                resource(ResourceKind.ENTITY, 1).withId(Ids.newId(ResourceKind.AREA)), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listFilterAndPaging() {
        String tag = unique();
        for (int i = 0; i < 5; i++) {
            create(tagged(tag, i), null);
        }
        create(tagged("other-" + tag, 0), null);
        Page<Resource> p0 = store.list(ResourceKind.ENTITY, Map.of("name", tag), 0, 2);
        Page<Resource> p2 = store.list(ResourceKind.ENTITY, Map.of("name", tag), 2, 2);
        assertThat(p0.total()).isEqualTo(5);
        assertThat(p0.items()).hasSize(2);
        assertThat(p2.items()).hasSize(1);
        assertThat(value(p0.items().get(0))).isEqualTo(0.0);
        assertThat(value(p2.items().get(0))).isEqualTo(4.0);
        assertThat(store.list(ResourceKind.ENTITY, Map.of("name", tag, "hp", "3.0"), 0, 10).total()).isEqualTo(1);
        assertThat(store.list(ResourceKind.AREA, Map.of("name", tag), 0, 10).total()).isZero();
    }

    @Test
    void filterKeysAreValidatedAndNeverInjected() {
        assertThatThrownBy(() -> store.list(ResourceKind.GAME, Map.of("a'; DROP TABLE resources;--", "x"), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // value is a bound parameter: a hostile value just matches nothing
        assertThat(store.list(ResourceKind.GAME, Map.of("tag", "x' OR '1'='1"), 0, 10).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources", Long.class)).isNotNull();
    }

    @Test
    void childrenFilteredByType() {
        String game = create(resource(ResourceKind.GAME, 1), null);
        String a1 = create(resource(ResourceKind.AREA, 1), game);
        String a2 = create(resource(ResourceKind.AREA, 2), game);
        Page<Resource> all = store.children(game, null, 0, 10);
        assertThat(all.items()).extracting(Resource::id).containsExactly(a1, a2);
        assertThat(store.children(game, ResourceKind.ENTITY, 0, 10).total()).isZero();
    }
}
