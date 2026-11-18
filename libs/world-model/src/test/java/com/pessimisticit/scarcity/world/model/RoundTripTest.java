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
        "rat-repeat.json"               // repeat behaviour, effect none
    })
    void entityStateRoundTrips(String file) {
        JsonNode input = Fixtures.state(file);

        Entity state = WorldJson.fromJson(input, Entity.class);

        assertThat(WorldJson.toTree(state)).isEqualTo(input);
        assertThat(Fixtures.tree(WorldJson.toJson(state))).isEqualTo(input);
    }

    @Test
    void typedAccessToTheOrbiterMidAction() {
        Entity s = WorldJson.fromJson(Fixtures.state("orbiter-mid-orbit-step.json"), Entity.class);

        assertThat(s.entityKind()).isEqualTo(EntityKind.NPC);
        assertThat(s.position()).isEqualTo(new Position(7.6, 5.5));
        assertThat(s.speed()).isEqualTo(0.5);
        assertThat(s.radius()).isNull();
        assertThat(s.actions()).containsExactly("orbit-step", "wait", "throw");
        assertThat(s.actionInProgress().timing()).isEqualTo(Timing.END);
        assertThat(s.actionInProgress().seq()).isEqualTo(8812L);
        assertThat(s.actionInProgress().duration()).isEqualTo(1.0);
        assertThat(s.actionInProgress().draw()).isEqualTo(new Draw.Categorical(List.of(2.0, 1.0, 1.0), 0.6180339887, 1));
        assertThat(s.behaviour()).isInstanceOfSatisfying(Behaviour.Random.class,
                r -> assertThat(r.choices()).extracting(Behaviour.Choice::weight).containsExactly(2.0, 1.0));
        assertThat(s.controller()).isNull();
    }

    @Test
    void controllersAreTyped() {
        Entity orbiter = WorldJson.fromJson(Fixtures.state("orbiter-controlled.json"), Entity.class);
        Entity player = WorldJson.fromJson(Fixtures.state("player.json"), Entity.class);

        assertThat(orbiter.controller()).isEqualTo(new Controller.Service("orbital"));
        assertThat(orbiter.behaviour()).isNull();
        assertThat(player.controller()).isEqualTo(new Controller.User("5d2a7e1c-0b7e-4c47-9f0e-2b3a8c1d4e5f"));
    }

    @Test
    void areaAndGameRoundTrip() {
        JsonNode area = Fixtures.tree("""
                {"resourceKind":"area","name":"Test room","width":10.0,"height":8.0,"time":1042.5,"depth":0,
                 "activeEntity":{"entity":"entity:00000000-0000-4000-8000-000000000004",
                       "controller":{"kind":"service","principal":"orbital"},"since":1040.0},
                 "pending":[{"entity":"entity:00000000-0000-4000-8000-00000000000a",
                             "controller":{"kind":"user","sub":"5d2a7e1c-0b7e-4c47-9f0e-2b3a8c1d4e5f"},
                             "since":1041.0}]}""");
        JsonNode game = Fixtures.tree("{\"resourceKind\":\"game\",\"name\":\"Test area\"}");

        Area a = WorldJson.fromJson(area, Area.class);

        assertThat(WorldJson.toTree(a)).isEqualTo(area);
        assertThat(a.time()).isEqualTo(1042.5);
        assertThat(a.activeEntity()).isEqualTo(new Turn("entity:00000000-0000-4000-8000-000000000004",
                new Controller.Service("orbital"), 1040.0));
        assertThat(a.queue()).hasSize(2).startsWith(a.activeEntity());
        assertThat(a.withTime(1050.0)).isEqualTo(new Area("Test room", 10.0, 8.0, 1050.0, 0, a.activeEntity(), a.pending()));
        assertThat(a.withQueue(a.queue())).isEqualTo(a);
        // Nobody up: no up key, an empty pending list (written, not dropped).
        Area none = WorldJson.fromJson(Fixtures.tree("""
                {"name":"Test room","width":10.0,"height":8.0,"time":0.0}"""), Area.class);
        assertThat(none.activeEntity()).isNull();
        assertThat(none.queue()).isEmpty();
        assertThat(WorldJson.toTree(none).has("activeEntity")).isFalse();
        assertThat(WorldJson.toTree(none).path("pending").isArray()).isTrue();
        assertThat(a.withQueue(List.of())).isEqualTo(new Area("Test room", 10.0, 8.0, 1042.5));
        assertThat(WorldJson.toTree(WorldJson.fromJson(game, Game.class))).isEqualTo(game);
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

    @ParameterizedTest
    @ValueSource(strings = {"fixed", "player", "npc", "mob"})
    void everyEntityKindRoundTrips(String kind) {
        String json = "{\"resourceKind\":\"entity\",\"entityKind\":\"" + kind + "\",\"prototype\":\"x\","
                + "\"name\":\"x\",\"position\":{\"x\":1.0,\"y\":1.0},\"overlap\":false,\"damageable\":false}";

        assertThat(WorldJson.toJson(WorldJson.fromJson(json, Entity.class))).isEqualTo(json);
    }

    @Test
    void depthDefaultsToZeroAndIsWritten() {
        String flat = "{\"resourceKind\":\"area\",\"name\":\"r\",\"width\":1.0,\"height\":1.0,\"time\":0.0,\"pending\":[]}";
        String deep = "{\"resourceKind\":\"area\",\"name\":\"r\",\"width\":1.0,\"height\":1.0,\"time\":0.0,\"depth\":2,\"pending\":[]}";

        assertThat(WorldJson.fromJson(flat, Area.class).depth()).isZero();
        assertThat(WorldJson.toJson(WorldJson.fromJson(flat, Area.class)))
                .isEqualTo(flat.replace("\"time\":0.0,", "\"time\":0.0,\"depth\":0,"));
        assertThat(WorldJson.toJson(WorldJson.fromJson(deep, Area.class))).isEqualTo(deep);
    }

    @Test
    void optionalValuesAreAbsentNotNull() {
        Entity s = new Fixed("rock", "rock").withPosition(new Position(1.0, 1.0));

        assertThat(WorldJson.toJson(s)).isEqualTo("{\"resourceKind\":\"entity\",\"entityKind\":\"fixed\","
                + "\"prototype\":\"rock\",\"name\":\"rock\",\"position\":{\"x\":1.0,\"y\":1.0},"
                + "\"overlap\":false,\"damageable\":false}");
    }
}
