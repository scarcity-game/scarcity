package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.rules.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** An entity's step reach and vision, for the client to draw (#517, docs/resources.md). */
class StepReachTest {

    @Test
    void theStepReachIsSpeedTimesTheLongestStepAndIsWritten() {
        Entity goblin = Prototypes.get("goblin").withSpeed(0.8);

        assertThat(goblin.stepReach()).isEqualTo(0.8 * Entity.MAX_STEP_DURATION);
        assertThat(Step.reach(goblin)).isEqualTo(goblin.stepReach());
        assertThat(WorldJson.toObject(goblin).get("stepReach").asDouble()).isEqualTo(4.0);
    }

    @Test
    void somethingThatDoesntMoveHasNoStepReach() {
        Entity rock = new Fixed("rock", "rock");

        assertThat(rock.stepReach()).isNull();
        assertThat(Step.reach(rock)).isZero();
        assertThat(WorldJson.toObject(rock).has("stepReach")).isFalse();
    }

    @Test
    void aStepReachReadBackIsDroppedAndDerivedAgain() {
        ObjectNode json = WorldJson.toObject(Prototypes.get("goblin"));
        json.put("stepReach", 99.0);

        Entity read = WorldJson.fromJson(json, Resource.class) instanceof Entity e ? e : null;

        assertThat(read).isNotNull();
        assertThat(read.stepReach()).isEqualTo(read.speed() * Entity.MAX_STEP_DURATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mouse", "goblin", "wild-dog"})
    void aCreatureSeesTenMetres(String prototype) {
        Entity creature = Prototypes.get(prototype);

        assertThat(creature.vision()).isEqualTo(10.0);
        assertThat(WorldJson.toObject(creature).get("vision").asDouble()).isEqualTo(10.0);
    }

    @Test
    void visionIsPositive() {
        Entity rock = new Fixed("rock", "rock");

        assertThatThrownBy(() -> rock.withVision(0.0)).hasMessageContaining("vision");
        assertThat(rock.withVision(3.0).withVision(null).vision()).isNull();
    }
}
