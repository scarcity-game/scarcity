package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** EntityTemplate: an entity state without position or action, placed with {@code at}. */
class EntityTemplateTest {

    private static final String PEBBLE = """
            {"kind":"mobile","name":"pebble","speed":6.0,"actions":["fly","decay"]}""";

    @Test
    void aTemplateRoundTripsAndPlacesAsAnIdleState() {
        EntityTemplate t = WorldJson.fromJson(PEBBLE, EntityTemplate.class);
        assertThat(WorldJson.toTree(t)).isEqualTo(Fixtures.tree(PEBBLE));
        EntityState s = t.at(new Position(8.0, 4.0));
        assertThat(s.position()).isEqualTo(new Position(8.0, 4.0));
        assertThat(s.idle()).isTrue();
        assertThat(WorldJson.toTree(s)).isEqualTo(Fixtures.tree("""
                {"kind":"mobile","name":"pebble","position":{"x":8.0,"y":4.0},"speed":6.0,"actions":["fly","decay"]}"""));
    }

    @Test
    void aTemplateHasNoPositionAndNoAction() {
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"kind":"fixed","name":"rubble","position":{"x":1.0,"y":1.0}}""", EntityTemplate.class))
                .hasMessage("EntityTemplate at 'position': position: unknown key");
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"kind":"fixed","name":"rubble","action":{}}""", EntityTemplate.class))
                .hasMessage("EntityTemplate at 'action': action: unknown key");
    }

    @Test
    void aTemplateHasTheStatesChecks() {
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"kind":"mobile","name":"pebble","speed":0.0}""", EntityTemplate.class))
                .hasMessage("EntityTemplate: speed: must be > 0, got 0.0");
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"kind":"mobile"}""", EntityTemplate.class))
                .hasMessage("EntityTemplate at 'name': name: required");
    }
}
