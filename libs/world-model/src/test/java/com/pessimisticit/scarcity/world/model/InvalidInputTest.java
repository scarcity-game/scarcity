package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

/** Bad input gives a WorldJsonException whose message names the key and where it is. */
class InvalidInputTest {

    private static AbstractThrowableAssert<?, ? extends Throwable> entity(String json) {
        return assertThatThrownBy(() -> WorldJson.fromJson(json, EntityState.class))
                .isInstanceOf(WorldJsonException.class);
    }

    @Test
    void missingPosition() {
        entity("{\"entityKind\":\"mobile\",\"name\":\"orbiter\",\"speed\":0.5}")
                .hasMessage("EntityState at 'position': position: required");
    }

    @Test
    void nonFiniteFloat() {
        // JSON has no NaN or Infinity, but a number too large for a double reads as Infinity.
        entity("{\"entityKind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":1e400,\"y\":0.0}}")
                .hasMessageContaining("at 'position'")
                .hasMessageContaining("x: must be a finite number, got Infinity");
        entity("{\"entityKind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":1.0,\"y\":0.0},\"speed\":-1e400}")
                .hasMessageContaining("speed: must be a finite number");
    }

    @Test
    void unknownBehaviourKind() {
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"zigzag","actionSpec":{"type":"wait","params":{}}}}""")
                .hasMessage("EntityState at 'behaviour': kind: unknown Behaviour 'zigzag'");
    }

    @Test
    void missingBehaviourKind() {
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"actionSpec":{"type":"wait","params":{}}}}""")
                .hasMessage("EntityState at 'behaviour': kind: required");
    }

    @Test
    void unknownControllerKindAndDist() {
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},"controller":{"kind":"ai"}}""")
                .hasMessage("EntityState at 'controller': kind: unknown Controller 'ai'");
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"none",
                           "draw":{"dist":"poisson","value":3}}}""")
                .hasMessage("EntityState at 'actionInProgress.draw': dist: unknown Draw 'poisson'");
    }

    @Test
    void unknownEntityKind() {
        entity("{\"entityKind\":\"mob\",\"name\":\"o\",\"position\":{\"x\":1.0,\"y\":0.0}}")
                .hasMessageContaining("at 'entityKind'");
    }

    @Test
    void numbersAreNotCoercedFromStrings() {
        entity("{\"entityKind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":\"1.0\",\"y\":0.0}}")
                .hasMessageContaining("at 'position.x'");
    }

    @Test
    void behaviourAndControllerTogether() {
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"repeat","actionSpec":{"type":"wait","params":{"duration":1.0}}},
                 "controller":{"kind":"service","principal":"orbital"}}""")
                .hasMessageContaining("controller: an entity with a behaviour has no controller");
    }

    @Test
    void actionInProgressIsChecked() {
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":2.0,"end":1.0,"timing":"none"}}""")
                .hasMessageContaining("at 'actionInProgress'")
                .hasMessageContaining("end: must not be before start");
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"later"}}""")
                .hasMessageContaining("at 'actionInProgress.timing'");
        entity("""
                {"entityKind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "actionInProgress":{"type":"wait","params":{},"start":0.0,"end":1.0,"timing":"none","speed":3}}""")
                .hasMessageContaining("at 'actionInProgress.speed'");
    }

    @Test
    void unknownKeysAreRejected() {
        entity("""
                {"entityKind":"fixed","name":"torch","position":{"x":0.5,"y":7.5},"light":{"radius":3.5}}""")
                .hasMessage("EntityState at 'light': light: unknown key");
        assertThatThrownBy(() -> WorldJson.fromJson("{\"name\":\"g\",\"seed\":42}", GameState.class))
                .hasMessage("GameState at 'seed': seed: unknown key");
    }

    @Test
    void areaWithoutTime() {
        // The deployed area has a clock anchor and no time until #111 upgrades it.
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"name":"Test room","width":10.0,"height":8.0,"clock":{"gameTime":0.0,"wallTime":1,"rate":1.0}}""",
                AreaState.class))
                .isInstanceOf(WorldJsonException.class)
                .hasMessage("AreaState at 'time': time: required");
        assertThatThrownBy(() -> WorldJson.fromJson("{\"name\":\"Test room\",\"width\":10.0,\"height\":8.0}",
                AreaState.class))
                .hasMessageContaining("'time'");
    }

    @Test
    void malformedJson() {
        entity("{\"kind\":").hasMessageStartingWith("EntityState: ");
    }
}
