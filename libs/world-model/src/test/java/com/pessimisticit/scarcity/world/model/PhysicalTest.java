package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Physical attributes in SI units (#206, docs/resources.md): every entity but a placeholder. */
class PhysicalTest {

    private static final String ID = "entity:00000000-0000-4000-8000-000000000003";

    private static Entity stored(Entity e, String id) {
        Entity withId = e.withPosition(new Position(1.0, 1.0)).withId(id);
        return withId.withVersion(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mouse", "pebble", "pillar", "rubble", "stairs-down", "stairs-up"})
    void everyPrototypeIsAPhysicalObject(String prototype) {
        Entity e = Prototypes.get(prototype);

        assertThat(e.mass()).as("mass").isPositive();
        assertThat(e.radius()).as("radius").isPositive();
        assertThat(e.hp() != null).as("hp exactly when damageable").isEqualTo(e.damageable());
        assertThat(stored(e, ID).requireStored()).isNotNull();
    }

    @Test
    void stairsFromTheirFactoryHaveThePrototypesValues() {
        assertThat(Stairs.down("stairs down").radius()).isEqualTo(Prototypes.get(Stairs.DOWN).radius());
        assertThat(Stairs.up("stairs up").overlap()).isTrue();
    }

    @Test
    void onlyADamageableEntityHasHp() {
        Entity rock = new Fixed("rock", "rock");

        assertThatThrownBy(() -> rock.withHp(5.0)).hasMessageContaining("hp: only a damageable");
        assertThat(rock.withDamageable(true).withHp(5.0).hp()).isEqualTo(5.0);
        assertThat(rock.withDamageable(true).withHp(5.0).withDamageable(false).hp()).isNull();
    }

    @Test
    void aStoredEntityNeedsAMassARadiusAndHpWhenDamageable() {
        Entity rock = stored(new Fixed("rock", "rock"), ID);

        assertThatThrownBy(rock::requireStored).hasMessageContaining("mass");
        assertThatThrownBy(rock.withMass(10.0)::requireStored).hasMessageContaining("radius");
        Entity physical = rock.withMass(10.0).withRadius(0.1);
        assertThat(physical.requireStored()).isNotNull();
        assertThatThrownBy(physical.withDamageable(true)::requireStored).hasMessageContaining("hp");
    }

    @Test
    void aPlaceholderIsNotPhysical() {
        Entity traveller = Prototypes.get("mouse").withActions(List.of("step")).withId(ID);
        Entity placeholder = stored(new Placeholder("mouse", traveller), "entity:00000000-0000-4000-8000-000000000004");

        assertThat(placeholder.physical()).isFalse();
        assertThat(placeholder.requireStored()).isNotNull();
    }
}
