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
        entity("{\"kind\":\"mobile\",\"name\":\"orbiter\",\"speed\":0.5}")
                .hasMessageContaining("'position'");
    }

    @Test
    void nonFiniteFloat() {
        // JSON has no NaN or Infinity, but a number too large for a double reads as Infinity.
        entity("{\"kind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":1e400,\"y\":0.0}}")
                .hasMessageContaining("at 'position'")
                .hasMessageContaining("x: must be a finite number, got Infinity");
        entity("{\"kind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":1.0,\"y\":0.0},\"speed\":-1e400}")
                .hasMessageContaining("speed: must be a finite number");
    }

    @Test
    void unknownBehaviourKind() {
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"zigzag","action":{"type":"wait","params":{}}}}""")
                .hasMessage("EntityState at 'behaviour': kind: unknown Behaviour 'zigzag'");
    }

    @Test
    void missingBehaviourKind() {
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"action":{"type":"wait","params":{}}}}""")
                .hasMessage("EntityState at 'behaviour': kind: required");
    }

    @Test
    void unknownControllerKindAndDist() {
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},"controller":{"kind":"ai"}}""")
                .hasMessage("EntityState at 'controller': kind: unknown Controller 'ai'");
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "action":{"type":"wait","params":{},"start":0.0,"end":1.0,"effect":"none",
                           "draw":{"dist":"poisson","value":3}}}""")
                .hasMessage("EntityState at 'action.draw': dist: unknown Draw 'poisson'");
    }

    @Test
    void unknownEntityKind() {
        entity("{\"kind\":\"mob\",\"name\":\"o\",\"position\":{\"x\":1.0,\"y\":0.0}}")
                .hasMessageContaining("at 'kind'");
    }

    @Test
    void numbersAreNotCoercedFromStrings() {
        entity("{\"kind\":\"mobile\",\"name\":\"o\",\"position\":{\"x\":\"1.0\",\"y\":0.0}}")
                .hasMessageContaining("at 'position.x'");
    }

    @Test
    void behaviourAndControllerTogether() {
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "behaviour":{"kind":"repeat","action":{"type":"wait","params":{"duration":1.0}}},
                 "controller":{"kind":"service","principal":"orbital"}}""")
                .hasMessageContaining("controller: an entity with a behaviour has no controller");
    }

    @Test
    void actionInProgressIsChecked() {
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "action":{"type":"wait","params":{},"start":2.0,"end":1.0,"effect":"none"}}""")
                .hasMessageContaining("at 'action'")
                .hasMessageContaining("end: must not be before start");
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "action":{"type":"wait","params":{},"start":0.0,"end":1.0,"effect":"later"}}""")
                .hasMessageContaining("at 'action.effect'");
        entity("""
                {"kind":"mobile","name":"o","position":{"x":1.0,"y":0.0},
                 "action":{"type":"wait","params":{},"start":0.0,"end":1.0,"effect":"none","speed":3}}""")
                .hasMessageContaining("at 'action.speed'");
    }

    @Test
    void areaWithoutTime() {
        // The deployed area has a clock anchor and no time until #111 upgrades it.
        assertThatThrownBy(() -> WorldJson.fromJson("""
                {"name":"Test room","width":10.0,"height":8.0,"clock":{"gameTime":0.0,"wallTime":1,"rate":1.0}}""",
                AreaState.class))
                .isInstanceOf(WorldJsonException.class)
                .hasMessageContaining("'time'");
    }

    @Test
    void malformedJson() {
        entity("{\"kind\":").hasMessageStartingWith("EntityState: ");
    }
}
