package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ApplyChildrenTest extends AbstractDbTest {

    @Test
    void mixedCreatesDeletesAndState() {
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));
        long initialVersion;

        // Create 3 entities first
        String ent1 = create(ResourceType.ENTITY, area, state("kind", "mobile"));
        String ent2 = create(ResourceType.ENTITY, area, state("kind", "static"));
        String ent3 = create(ResourceType.ENTITY, area, state("kind", "npc"));

        initialVersion = store.get(area).orElseThrow().version();
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent1, ent2, ent3);

        // Create new resources for creation
        Resource newEnt1 = Resource.newResource(ResourceType.ENTITY, state("kind", "new1"));
        Resource newEnt2 = Resource.newResource(ResourceType.ENTITY, state("kind", "new2"));

        // Apply changes: 2 creates + 2 deletes + new state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(newEnt1, newEnt2),
                List.of(ent1, ent2), state("w", 20.5)));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert children list: kept child (ent3) + created resources in order
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent3, newEnt1.id(), newEnt2.id());

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent3, newEnt1.id(), newEnt2.id());

        // Assert state is updated
        assertThat(store.get(area).orElseThrow().state().get("w").asDouble()).isEqualTo(20.5);

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
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));
        long initialVersion = store.get(area).orElseThrow().version();

        // Create new resources for creation
        Resource newEnt1 = Resource.newResource(ResourceType.ENTITY, state("kind", "new1"));
        Resource newEnt2 = Resource.newResource(ResourceType.ENTITY, state("kind", "new2"));

        // Apply changes: 2 creates + null deletes + null state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(newEnt1, newEnt2),
                List.of(), null));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state unchanged
        assertThat(store.get(area).orElseThrow().state().get("w").asDouble()).isEqualTo(10.5);

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
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));

        // Create 2 entities first
        String ent1 = create(ResourceType.ENTITY, area, state("kind", "mobile"));
        String ent2 = create(ResourceType.ENTITY, area, state("kind", "static"));
        long initialVersion = store.get(area).orElseThrow().version();

        // Apply changes: null creates + 1 delete + null state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(ent1), null));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state unchanged
        assertThat(store.get(area).orElseThrow().state().get("w").asDouble()).isEqualTo(10.5);

        // Assert children list contains only the kept child
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent2);

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent2);

        // Assert deleted resources no longer exist
        assertThat(store.get(ent1)).isEmpty();
    }

    @Test
    void stateOnly() {
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));

        // Create an entity first
        String ent = create(ResourceType.ENTITY, area, state("kind", "mobile"));
        long initialVersion = store.get(area).orElseThrow().version();

        // Apply changes: null creates + null deletes + new state
        ChildrenWrite result = tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(), state("w", 20.5)));

        // Assert version bump
        assertThat(result.parentVersion()).isEqualTo(initialVersion + 1);
        assertThat(store.get(area).orElseThrow().version()).isEqualTo(initialVersion + 1);

        // Assert state is updated
        assertThat(store.get(area).orElseThrow().state().get("w").asDouble()).isEqualTo(20.5);

        // Assert children unchanged
        assertThat(store.get(area).orElseThrow().children()).containsExactly(ent);

        // Assert result children matches store
        assertThat(result.children()).containsExactly(ent);
    }

    @Test
    void deleteOfNonChildFails() {
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area1 = create(ResourceType.AREA, game, state("w", 10.5));
        String area2 = create(ResourceType.AREA, game, state("w", 20.5));

        // Create an entity in area1
        String ent = create(ResourceType.ENTITY, area1, state("kind", "mobile"));
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
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));

        // Create a new resource
        Resource newEnt = Resource.newResource(ResourceType.ENTITY, state("kind", "new"));

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
        String game = create(ResourceType.GAME, null, state("n", unique()));
        String area = create(ResourceType.AREA, game, state("w", 10.5));

        // Try to call with empty lists and null state (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(area, List.of(),
                List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingParent() {
        String game = create(ResourceType.GAME, null, state("n", unique()));
        // Create a new area id (not existing in DB)
        Resource newArea = Resource.newResource(ResourceType.AREA, state("w", 10.5));

        // Try to apply children to a non-existent parent (should fail)
        assertThatThrownBy(() -> tx.execute(s -> store.applyChildren(newArea.id(), List.of(),
                List.of(), state("w", 1))))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
