package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import org.junit.jupiter.api.Test;

class FramesTest {

    private static JsonNode json(String text) {
        return WorldJson.fromJson(text, JsonNode.class);
    }

    @Test
    void theFramesOrbitalSends() {
        assertThat(Frames.auth("t")).isEqualTo("{\"type\":\"auth\",\"token\":\"t\"}");
        assertThat(Frames.subscribe("area:a")).isEqualTo("{\"type\":\"subscribe\",\"area\":\"area:a\"}");
    }

    @Test
    void aTurnIsReadOnlyForTheArea() {
        String turn = "{\"type\":\"turn\",\"area\":\"area:a\",\"entity\":\"entity:e\",\"version\":7,\"since\":2.5}";
        assertThat(Frames.turn(json(turn), "area:a")).contains(new TurnFrame("area:a", "entity:e", 7, 2.5));
        assertThat(Frames.turn(json(turn), "area:b")).isEmpty();
        assertThat(Frames.turn(json("{\"type\":\"digest\",\"area\":\"area:a\"}"), "area:a")).isEmpty();
    }
}
