package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** EntityTemplate: the name of a prototype, placed with {@code at}. */
class EntityTemplateTest {

    @Test
    void aTemplateRoundTripsAndPlacesAsAnIdleEntity() {
        EntityTemplate t = WorldJson.fromJson("{\"prototype\":\"pebble\"}", EntityTemplate.class);
        assertThat(WorldJson.toJson(t)).isEqualTo("{\"prototype\":\"pebble\"}");
        Entity e = t.at(new Position(8.0, 4.0));
        assertThat(e.position()).isEqualTo(new Position(8.0, 4.0));
        assertThat(e.idle()).isTrue();
        assertThat(e).isInstanceOf(Mob.class);
        assertThat(WorldJson.toTree(e)).isEqualTo(Fixtures.tree("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"pebble","name":"pebble","position":{"x":8.0,"y":4.0},"speed":6.0,"actions":["fly","decay"]}"""));
    }

    @Test
    void aTemplateNamesAKnownPrototype() {
        assertThatThrownBy(() -> WorldJson.fromJson("{\"prototype\":\"dragon\"}", EntityTemplate.class))
                .hasMessageContaining("unknown prototype 'dragon'");
        assertThatThrownBy(() -> WorldJson.fromJson("{\"prototype\":\"../pebble\"}", EntityTemplate.class))
                .hasMessageContaining("not a prototype name");
        assertThatThrownBy(() -> WorldJson.fromJson("{}", EntityTemplate.class))
                .hasMessage("EntityTemplate at 'prototype': prototype: required");
        assertThatThrownBy(() -> WorldJson.fromJson("{\"prototype\":\"pebble\",\"hp\":1.0}", EntityTemplate.class))
                .hasMessage("EntityTemplate at 'hp': hp: unknown key");
    }

    @Test
    void aPrototypeWithItsOwnClassReadsAsIt() {
        assertThat(Prototypes.get("stairs")).isInstanceOf(Stairs.class);
        assertThat(Prototypes.get("pillar")).isInstanceOf(Fixed.class).isNotInstanceOf(Stairs.class);
        assertThat(Prototypes.get("mouse")).isInstanceOf(Mob.class);
    }
}
