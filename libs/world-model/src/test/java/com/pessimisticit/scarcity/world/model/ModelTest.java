package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** The records used from Java: immutability, withers, ids. */
class ModelTest {

    private static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";

    @Test
    void paramsCannotBeChangedFromOutside() {
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("duration", 1.0);
        ActionInProgress a = new ActionInProgress("wait", params, 0.0, 1.0, Timing.NONE, null, null);

        params.put("duration", 9.0);
        a.params().put("duration", 7.0);

        assertThat(a.params().get("duration").asDouble()).isEqualTo(1.0);
    }

    @Test
    void withersKeepEverythingElse() {
        Entity pillar = WorldJson.fromJson(Fixtures.state("pillar.json"), Entity.class);
        ActionInProgress decay = new ActionInProgress("decay",
                JsonNodeFactory.instance.objectNode().put("duration", 20.0), 3.0, 23.0, Timing.END, 5L, null);

        Entity damaged = pillar.withHp(97.5);
        Entity busy = damaged.withAction(decay);

        assertThat(damaged.hp()).isEqualTo(97.5);
        assertThat(damaged.radius()).isEqualTo(pillar.radius());
        assertThat(busy.idle()).isFalse();
        assertThat(busy.withAction(null)).isEqualTo(damaged);
        assertThat(pillar).isEqualTo(WorldJson.fromJson(Fixtures.state("pillar.json"), Entity.class));
    }

    @Test
    void resourcesCheckTheirIds() {
        Entity pillar = WorldJson.fromJson(Fixtures.state("pillar.json"), Entity.class);

        Entity stored = pillar.withId(PILLAR).withVersion(1);
        assertThat(stored.withHp(1.0).version()).isEqualTo(1);
        assertThat(stored.stored()).isTrue();
        assertThat(pillar.stored()).isFalse();
        assertThatThrownBy(() -> pillar.withId("area:00000000-0000-4000-8000-000000000002"))
                .hasMessageContaining("not a well-formed entity id");
        assertThatThrownBy(() -> pillar.withId("entity:00000000-0000-4000-8000-00000000000A"))
                .hasMessageContaining("not a well-formed entity id");
        assertThatThrownBy(() -> pillar.withVersion(0)).hasMessageContaining("version");
        assertThatThrownBy(() -> new Area("r", 1.0, 1.0, 0.0).withId("area:nope"))
                .hasMessageContaining("not a well-formed area id");
        assertThatThrownBy(() -> pillar.requireStored()).hasMessageContaining("id: required");
    }

    @Test
    void areaContainsIsHalfOpen() {
        Area room = new Area("Test room", 10.0, 8.0, 0.0);

        assertThat(room.contains(new Position(0.0, 0.0))).isTrue();
        assertThat(room.contains(new Position(9.999, 7.999))).isTrue();
        assertThat(room.contains(new Position(10.0, 4.0))).isFalse();
        assertThat(room.contains(new Position(5.0, -0.001))).isFalse();
    }
}
