package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** JSON -> record -> JSON gives the input back, compared as JSON trees. */
class RoundTripTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "pillar.json",                  // seed today
        "orbiter.json",                 // seed today: random behaviour, throw at a target id
        "orbiter-mid-orbit-step.json",  // action in progress with seq and a categorical draw
        "orbiter-controlled.json",      // the new shape: service controller, step in progress
        "player.json",                  // user controller, radius, hp
        "pebble-flying.json",           // fly with nested params
        "rat-repeat.json",              // repeat behaviour, effect none
        "torch-with-content-keys.json"  // keys the model doesn't know, including a null in an array
    })
    void entityStateRoundTrips(String file) {
        JsonNode input = Fixtures.state(file);

        EntityState state = WorldJson.fromJson(input, EntityState.class);

        assertThat(WorldJson.toTree(state)).isEqualTo(input);
        assertThat(Fixtures.tree(WorldJson.toJson(state))).isEqualTo(input);
    }

    @Test
    void typedAccessToTheOrbiterMidAction() {
        EntityState s = WorldJson.fromJson(Fixtures.state("orbiter-mid-orbit-step.json"), EntityState.class);

        assertThat(s.kind()).isEqualTo(EntityKind.MOBILE);
        assertThat(s.position()).isEqualTo(new Position(7.6, 5.5));
        assertThat(s.speed()).isEqualTo(0.5);
        assertThat(s.radius()).isNull();
        assertThat(s.actions()).containsExactly("orbit-step", "wait", "throw");
        assertThat(s.action().effect()).isEqualTo(Timing.END);
        assertThat(s.action().seq()).isEqualTo(8812L);
        assertThat(s.action().duration()).isEqualTo(1.0);
        assertThat(s.action().draw()).isEqualTo(new Draw.Categorical(List.of(2.0, 1.0, 1.0), 0.6180339887, 1));
        assertThat(s.behaviour()).isInstanceOfSatisfying(Behaviour.Random.class,
                r -> assertThat(r.choices()).extracting(Behaviour.Choice::weight).containsExactly(2.0, 1.0));
        assertThat(s.controller()).isNull();
        assertThat(s.extra()).isEmpty();
    }

    @Test
    void controllersAreTyped() {
        EntityState orbiter = WorldJson.fromJson(Fixtures.state("orbiter-controlled.json"), EntityState.class);
        EntityState player = WorldJson.fromJson(Fixtures.state("player.json"), EntityState.class);

        assertThat(orbiter.controller()).isEqualTo(new Controller.Service("orbital"));
        assertThat(orbiter.behaviour()).isNull();
        assertThat(player.controller()).isEqualTo(new Controller.User("5d2a7e1c-0b7e-4c47-9f0e-2b3a8c1d4e5f"));
    }

    @Test
    void unknownKeysSurviveAChange() {
        EntityState torch = WorldJson.fromJson(Fixtures.state("torch-with-content-keys.json"), EntityState.class);

        EntityState moved = torch.withPosition(new Position(1.0, 7.0));

        JsonNode out = WorldJson.toTree(moved);
        JsonNode in = Fixtures.state("torch-with-content-keys.json");
        assertThat(torch.extra()).containsOnlyKeys("light", "flicker", "lit", "tags");
        assertThat(out.get("light")).isEqualTo(in.get("light"));
        assertThat(out.get("flicker")).isEqualTo(in.get("flicker"));
        assertThat(out.get("position")).isEqualTo(Fixtures.tree("{\"x\":1.0,\"y\":7.0}"));
    }

    @Test
    void areaAndGameRoundTrip() {
        JsonNode area = Fixtures.tree("""
                {"name":"Test room","width":10.0,"height":8.0,"time":1042.5,"weather":{"rain":0.2}}""");
        JsonNode game = Fixtures.tree("{\"name\":\"Test area\"}");

        AreaState a = WorldJson.fromJson(area, AreaState.class);

        assertThat(WorldJson.toTree(a)).isEqualTo(area);
        assertThat(a.time()).isEqualTo(1042.5);
        assertThat(WorldJson.toTree(a.withTime(1050.0)).get("weather")).isEqualTo(area.get("weather"));
        assertThat(WorldJson.toTree(WorldJson.fromJson(game, GameState.class))).isEqualTo(game);
    }

    @Test
    void drawsRoundTrip() {
        for (String json : List.of(
                "{\"dist\":\"gaussian\",\"mean\":0.0,\"sd\":0.2,\"value\":0.031}",
                "{\"dist\":\"gaussian\",\"mean\":2.0,\"sd\":0.5,\"truncate\":{\"min\":0.5,\"max\":4.0},\"value\":2.37}",
                "{\"dist\":\"bernoulli\",\"p\":0.5,\"u\":0.12,\"value\":true}",
                "{\"dist\":\"categorical\",\"weights\":[1.0,1.0],\"u\":0.6180339887,\"value\":1}")) {
            JsonNode tree = Fixtures.tree(json);
            assertThat(WorldJson.toTree(WorldJson.fromJson(tree, Draw.class))).as(json).isEqualTo(tree);
        }
    }

    @Test
    void optionalValuesAreAbsentNotNull() {
        EntityState s = new EntityState(EntityKind.FIXED, "rock", new Position(1.0, 1.0),
                null, null, null, null, null, null, null, null);

        assertThat(WorldJson.toJson(s)).isEqualTo("{\"kind\":\"fixed\",\"name\":\"rock\",\"position\":{\"x\":1.0,\"y\":1.0}}");
    }
}
