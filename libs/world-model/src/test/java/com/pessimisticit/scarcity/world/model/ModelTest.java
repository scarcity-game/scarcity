package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
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
        EntityState pillar = WorldJson.fromJson(Fixtures.state("pillar.json"), EntityState.class);
        ActionInProgress decay = new ActionInProgress("decay",
                JsonNodeFactory.instance.objectNode().put("duration", 20.0), 3.0, 23.0, Timing.END, 5L, null);

        EntityState damaged = pillar.withHp(97.5);
        EntityState busy = damaged.withAction(decay);

        assertThat(damaged.hp()).isEqualTo(97.5);
        assertThat(damaged.radius()).isEqualTo(pillar.radius());
        assertThat(busy.idle()).isFalse();
        assertThat(busy.withAction(null)).isEqualTo(damaged);
        assertThat(pillar).isEqualTo(WorldJson.fromJson(Fixtures.state("pillar.json"), EntityState.class));
    }

    @Test
    void extraCannotShadowAKnownKey() {
        assertThatThrownBy(() -> new GameState("g", Map.of("name", JsonNodeFactory.instance.textNode("x"))))
                .hasMessage("name: is a known key, not an extra one");
    }

    @Test
    void resourcesCheckTheirIds() {
        EntityState pillar = WorldJson.fromJson(Fixtures.state("pillar.json"), EntityState.class);

        assertThat(new Entity(PILLAR, 1, pillar).withState(pillar.withHp(1.0)).version()).isEqualTo(1);
        assertThatThrownBy(() -> new Entity("area:00000000-0000-4000-8000-000000000002", 1, pillar))
                .hasMessageContaining("not a well-formed entity id");
        assertThatThrownBy(() -> new Entity("entity:00000000-0000-4000-8000-00000000000A", 1, pillar))
                .hasMessageContaining("not a well-formed entity id");
        assertThatThrownBy(() -> new Entity(PILLAR, 0, pillar)).hasMessageContaining("version");
        assertThatThrownBy(() -> new Area("area:nope", 1, new AreaState("r", 1.0, 1.0, 0.0)))
                .hasMessageContaining("not a well-formed area id");
    }

    @Test
    void areaContainsIsHalfOpen() {
        AreaState room = new AreaState("Test room", 10.0, 8.0, 0.0);

        assertThat(room.contains(new Position(0.0, 0.0))).isTrue();
        assertThat(room.contains(new Position(9.999, 7.999))).isTrue();
        assertThat(room.contains(new Position(10.0, 4.0))).isFalse();
        assertThat(room.contains(new Position(5.0, -0.001))).isFalse();
    }
}
