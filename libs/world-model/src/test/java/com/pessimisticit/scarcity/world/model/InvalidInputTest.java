package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

/** Bad input gives a WorldJsonException whose message names the key and where it is. */
class InvalidInputTest {

    private static AbstractThrowableAssert<?, ? extends Throwable> entity(String json) {
        return assertThatThrownBy(() -> WorldJson.fromJson(json, Entity.class))
                .isInstanceOf(WorldJsonException.class);
    }

    @Test
    void missingPosition() {
        Entity unplaced = WorldJson.fromJson(
                "{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"orbiter\",\"name\":\"orbiter\",\"speed\":0.5}",
                Entity.class);
        assertThatThrownBy(() -> unplaced.withId("entity:00000000-0000-4000-8000-000000000001").withVersion(1)
                .requireStored()).hasMessage("position: required");
    }

    @Test
    void nonFiniteFloat() {
        // JSON has no NaN or Infinity, but a number too large for a double reads as Infinity.
        entity("{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"o\",\"name\":\"o\",\"position\":{\"x\":1e400,\"y\":0.0}}")
                .hasMessageContaining("at 'position'")
                .hasMessageContaining("x: must be a finite number, got Infinity");
        entity("{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"o\",\"name\":\"o\",\"position\":{\"x\":1.0,\"y\":0.0},\"speed\":-1e400}")
                .hasMessageContaining("speed: must be a finite number");
    }

    @Test
    void unknownBehaviourKind() {
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"zigzag","actionSpec":{"type":"wait","params":{}}}}""")
                .hasMessage("Entity at 'behaviour': kind: unknown Behaviour 'zigzag'");
    }

    @Test
    void missingBehaviourKind() {
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"actionSpec":{"type":"wait","params":{}}}}""")
                .hasMessage("Entity at 'behaviour': kind: required");
    }

    @Test
    void unknownControllerKindAndDist() {
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},"controller":{"kind":"ai"}}""")
                .hasMessage("Entity at 'controller': kind: unknown Controller 'ai'");
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"none",
                           "draw":{"dist":"poisson","value":3}}}""")
                .hasMessage("Entity at 'actionInProgress.draw': dist: unknown Draw 'poisson'");
    }

    @Test
    void unknownEntityKind() {
        entity("{\"resourceKind\":\"entity\",\"entityKind\":\"ogre\",\"prototype\":\"o\",\"name\":\"o\"}")
                .hasMessageContaining("at 'entityKind'");
    }

    @Test
    void numbersAreNotCoercedFromStrings() {
        entity("{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"o\",\"name\":\"o\",\"position\":{\"x\":\"1.0\",\"y\":0.0}}")
                .hasMessageContaining("at 'position.x'");
    }

    @Test
    void behaviourAndControllerTogether() {
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"repeat","actionSpec":{"type":"wait","params":{"duration":1.0}}},
                 "controller":{"kind":"service","principal":"orbital"}}""")
                .hasMessageContaining("controller: an entity with a behaviour has no controller");
    }

    @Test
    void actionInProgressIsChecked() {
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":2.0,"end":1.0,"timing":"none"}}""")
                .hasMessageContaining("at 'actionInProgress'")
                .hasMessageContaining("end: must not be before start");
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"later"}}""")
                .hasMessageContaining("at 'actionInProgress.timing'");
        entity("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"o","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"none","speed":3}}""")
                .hasMessageContaining("at 'actionInProgress.speed'");
    }

    @Test
    void unknownKeysAreRejected() {
        entity("""
                {"resourceKind":"entity","entityKind":"fixed","prototype":"torch","name":"torch","position":{"x":0.5,"y":7.5},"light":{"radius":3.5}}""")
                .hasMessage("Entity at 'light': light: unknown key");
        assertThatThrownBy(() -> WorldJson.fromJson("{\"resourceKind\":\"game\",\"name\":\"g\",\"seed\":42}", Game.class))
                .hasMessage("Game at 'seed': seed: unknown key");
    }

    @Test
    void areaWithoutTime() {
        assertThatThrownBy(() -> WorldJson.fromJson(
                "{\"resourceKind\":\"area\",\"name\":\"Test room\"}", Area.class))
                .isInstanceOf(WorldJsonException.class)
                .hasMessage("Area: time: required");
    }

    @Test
    void malformedJson() {
        entity("{\"kind\":").hasMessageStartingWith("Entity: ");
    }
}
