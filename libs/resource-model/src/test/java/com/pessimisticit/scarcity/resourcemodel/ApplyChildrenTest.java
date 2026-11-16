package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import org.junit.jupiter.api.Test;

class ApplyChildrenTest extends AbstractDbTest {

    @Test
    void mixedCreatesDeletesAndState() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);
        long initialVersion;

        // Create 3 entities first
        String ent1 = create(resource(ResourceKind.ENTITY, "mobile"), area);
        String ent2 = create(resource(ResourceKind.ENTITY, "static"), area);
        String ent3 = create(resource(ResourceKind.ENTITY, "npc"), area);

        initialVersion = store.get(area).orElseThrow().version();
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent1, ent2, ent3);

        // Create new resources for creation
        Resource newEnt1 = resource(ResourceKind.ENTITY, "new1");
        Resource newEnt2 = resource(ResourceKind.ENTITY, "new2");

        // Apply changes: 2 creates + 2 deletes + new state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(newEnt1, newEnt2),
                List.of(ent1, ent2), resource(ResourceKind.AREA, 20.5).withId(area)));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert children list: kept child (ent3) + created resources in order
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent3, newEnt1.id(), newEnt2.id());

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent3, newEnt1.id(), newEnt2.id());

        // Assert state is updated
        assertThat(value(store.get(area).orElseThrow())).isEqualTo(20.5);

        // Assert created resources have correct version and parentVersion
        assertThat(result.created()).hasSize(2);
        assertThat(result.created().get(0).version()).isEqualTo(1);
        assertThat(result.created().get(0).parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(result.created().get(1).version()).isEqualTo(1);
        assertThat(result.created().get(1).parentVersion()).isEqualTo(initialVersion + 1);

        // Assert deleted resources no longer exist
        assertThat(store.get(ent1)).isEmpty();
        assertThat(store.get(ent2)).isEmpty();
        assertThat(store.get(ent3).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void createsOnly() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);
        long initialVersion = store.get(area).orElseThrow().version();

        // Create new resources for creation
        Resource newEnt1 = resource(ResourceKind.ENTITY, "new1");
        Resource newEnt2 = resource(ResourceKind.ENTITY, "new2");

        // Apply changes: 2 creates + null deletes + null state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(newEnt1, newEnt2),
                List.of(), null));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state unchanged
        assertThat(value(store.get(area).orElseThrow())).isEqualTo(10.5);

        // Assert children list contains created resources in order
        assertThat(store.get(area).orElseThrow().children()).containsExactly(newEnt1.id(), newEnt2.id());

        // Assert result children matches store
        assertThat(result.children()).containsExactly(newEnt1.id(), newEnt2.id());

        // Assert created resources have correct version and parentVersion
        assertThat(result.created()).hasSize(2);
        assertThat(result.created().get(0).version()).isEqualTo(1);
        assertThat(result.created().get(0).parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(result.created().get(1).version()).isEqualTo(1);
        assertThat(result.created().get(1).parentVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    void deletesOnly() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);

        // Create 2 entities first
        String ent1 = create(resource(ResourceKind.ENTITY, "mobile"), area);
        String ent2 = create(resource(ResourceKind.ENTITY, "static"), area);
        long initialVersion = store.get(area).orElseThrow().version();

        // Apply changes: null creates + 1 delete + null state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(ent1), null));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state unchanged
        assertThat(value(store.get(area).orElseThrow())).isEqualTo(10.5);

        // Assert children list contains only the kept child
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent2);

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent2);

        // Assert deleted resources no longer exist
        assertThat(store.get(ent1)).isEmpty();
    }

    @Test
    void stateOnly() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);

        // Create an entity first
        String ent = create(resource(ResourceKind.ENTITY, "mobile"), area);
        long initialVersion = store.get(area).orElseThrow().version();

        // Apply changes: null creates + null deletes + new state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(), resource(ResourceKind.AREA, 20.5).withId(area)));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state is updated
        assertThat(value(store.get(area).orElseThrow())).isEqualTo(20.5);

        // Assert children unchanged
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent);
    }

    @Test
    void deleteOfNonChildFails() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area1 = create(resource(ResourceKind.AREA, 10.5), game);
        String area2 = create(resource(ResourceKind.AREA, 20.5), game);

        // Create an entity in area1
        String ent = create(resource(ResourceKind.ENTITY, "mobile"), area1);
        long initialVersion = store.get(area1).orElseThrow().version();

        // Try to delete the entity from area2 (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(area2, List.of(),
                List.of(ent), null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Assert parent version, children and other child are unchanged
        assertThat(store.get(area1).orElseThrow().version()).isEqualTo(initialVersion);
        assertThat(store.get(area1).orElseThrow().children()).containsExactly(ent);
        assertThat(store.get(area2).orElseThrow().children()).isEmpty();
    }

    @Test
    void createdAndDeletedInSameCallIsRejected() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);

        // Create a new resource
        Resource newEnt = resource(ResourceKind.ENTITY, "new");

        // Try to both create and delete the same ID (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(area, List.of(newEnt),
                List.of(newEnt.id()), null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Assert parent version unchanged and new id does not exist
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(1);
        assertThat(store.get(newEnt.id())).isEmpty();
    }

    @Test
    void emptyCallIsRejected() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        String area = create(resource(ResourceKind.AREA, 10.5), game);

        // Try to call with empty lists and null state (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingParent() {
        String game = create(resource(ResourceKind.GAME, unique()), null);
        // Create a new area id (not existing in DB)
        Resource newArea = resource(ResourceKind.AREA, 10.5);

        // Try to apply children to a non-existent parent (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(newArea.id(), List.of(),
                List.of(), resource(ResourceKind.AREA, 1).withId(newArea.id()))))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
