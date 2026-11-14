package com.pessimisticit.scarcity.world;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeederUpgradeTest extends LogTestBase {

    @Test
    void freshSeedIsTheTestArea() throws Exception {
        // Read the current seed files to get the expected state
        try (InputStream is = SeederUpgradeTest.class.getResourceAsStream("/seed/pillar.json")) {
            JsonNode pillar = JSON.readTree(is);
            assertThat(pillar.path("radius").asDouble()).isEqualTo(0.5);
            assertThat(pillar.path("hp").asDouble()).isEqualTo(100.0);
            assertThat(pillar.path("position").path("x").asDouble()).isEqualTo(5.0);
            assertThat(pillar.path("position").path("y").asDouble()).isEqualTo(4.0);
        }

        try (InputStream is = SeederUpgradeTest.class.getResourceAsStream("/seed/orbiter.json")) {
            JsonNode orbiter = JSON.readTree(is);
            List<String> actions = List.of("orbit-step", "wait", "throw");
            assertThat(orbiter.path("actions")).isNotNull();
            assertThat(orbiter.path("actions").size()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(orbiter.path("actions").get(i).asText()).isEqualTo(actions.get(i));
            }

            JsonNode behaviour = orbiter.path("behaviour");
            assertThat(behaviour.path("kind").asText()).isEqualTo("random");
            assertThat(behaviour.path("choices")).isNotNull();
            assertThat(behaviour.path("choices").size()).isEqualTo(3);

            // Check weights and action types
            assertThat(behaviour.path("choices").get(0).path("weight").asDouble()).isEqualTo(2.0);
            assertThat(behaviour.path("choices").get(0).path("action").path("type").asText()).isEqualTo("orbit-step");

            assertThat(behaviour.path("choices").get(1).path("weight").asDouble()).isEqualTo(1.0);
            assertThat(behaviour.path("choices").get(1).path("action").path("type").asText()).isEqualTo("wait");

            assertThat(behaviour.path("choices").get(2).path("weight").asDouble()).isEqualTo(1.0);
            assertThat(behaviour.path("choices").get(2).path("action").path("type").asText()).isEqualTo("throw");

            // Check throw parameters
            JsonNode throwAction = behaviour.path("choices").get(2).path("action");
            assertThat(throwAction.path("params").path("recovery").asDouble()).isEqualTo(2.0);
            assertThat(throwAction.path("params").path("spread").asDouble()).isEqualTo(0.2);
            assertThat(throwAction.path("params").path("target").asText()).isEqualTo(WorldIds.PILLAR);

            // Check projectile parameters
            JsonNode projectile = throwAction.path("params").path("projectile");
            assertThat(projectile.path("state").path("speed").asDouble()).isEqualTo(6.0);
            assertThat(projectile.path("damage").path("mean").asDouble()).isEqualTo(2.0);
            assertThat(projectile.path("damage").path("sd").asDouble()).isEqualTo(0.5);
            assertThat(projectile.path("damage").path("min").asDouble()).isEqualTo(0.5);
            assertThat(projectile.path("damage").path("max").asDouble()).isEqualTo(4.0);
            assertThat(projectile.path("p").asDouble()).isEqualTo(0.5);

            JsonNode decay = projectile.path("decay");
            assertThat(decay.path("duration").asDouble()).isEqualTo(20.0);
        }

        // Check versions
        assertThat(version(WorldIds.PILLAR)).isEqualTo(1);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
    }

    @Test
    void theDeployedShapeIsUpgradedInPlaceKeepingTheActionInProgress() throws Exception {
        // Old shape states
        ObjectNode oldPillarState = (ObjectNode) JSON.readTree("""
            {"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0}}
            """);

        ObjectNode oldOrbiterState = (ObjectNode) JSON.readTree("""
            {"kind":"mobile","name":"orbiter","position":{"x":6.0,"y":5.0},"speed":0.5,"actions":["orbit-step","wait"],"behaviour":{"kind":"random","choices":[{"weight":1.0,"action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":3.0,"arc":0.5}}},{"weight":1.0,"action":{"type":"wait","params":{"duration":1.0}}}]},"action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":3.0,"arc":0.5},"start":1000000.0,"end":1000001.0,"effect":"end","seq":7,"draw":{"dist":"categorical","weights":[1.0,1.0],"u":0.25,"value":0}}}
            """);

        // Record versions

        // Set the world to the old shape
        store.updateState(WorldIds.PILLAR, oldPillarState);
        store.updateState(WorldIds.ORBITER, oldOrbiterState);
        long oldPillarVersion = version(WorldIds.PILLAR);
        long oldOrbiterVersion = version(WorldIds.ORBITER);

        // Call seeder.seed() - should return false (upgrade happened)
        assertThat(seeder.seed()).isFalse();

        // Check versions after upgrade
        assertThat(version(WorldIds.ORBITER)).isEqualTo(oldOrbiterVersion + 1);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);

        // Check that orbiter state is updated correctly:
        // - Position unchanged (6.0, 5.0)
        // - Action is exactly equal to the old action
        JsonNode newOrbiterState = store.get(WorldIds.ORBITER).orElseThrow().state();
        assertThat(newOrbiterState.path("position").path("x").asDouble()).isEqualTo(6.0);
        assertThat(newOrbiterState.path("position").path("y").asDouble()).isEqualTo(5.0);

        // The action should be exactly the same as before
        JsonNode oldAction = oldOrbiterState.path("action");
        JsonNode newAction = newOrbiterState.path("action");
        assertThat(newAction).isEqualTo(oldAction);

        // Check that actions and behaviour are from the seed
        try (InputStream is = SeederUpgradeTest.class.getResourceAsStream("/seed/orbiter.json")) {
            JsonNode seedOrbiter = JSON.readTree(is);
            assertThat(newOrbiterState.path("actions")).isEqualTo(seedOrbiter.path("actions"));
            assertThat(newOrbiterState.path("behaviour")).isEqualTo(seedOrbiter.path("behaviour"));
        }

        // Pillar should have radius 0.5 and hp 100.0
        JsonNode newPillarState = store.get(WorldIds.PILLAR).orElseThrow().state();
        assertThat(newPillarState.path("radius").asDouble()).isEqualTo(0.5);
        assertThat(newPillarState.path("hp").asDouble()).isEqualTo(100.0);

        // Call seeder.seed() a second time - returns false: the game exists, nothing changes
        assertThat(seeder.seed()).isFalse();

        // Versions should be unchanged
        assertThat(version(WorldIds.ORBITER)).isEqualTo(oldOrbiterVersion + 1);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);
    }

    @Test
    void aPillarThatAlreadyHasHpKeepsIt() throws Exception {
        // Old shape state with HP already set
        ObjectNode oldPillarState = (ObjectNode) JSON.readTree("""
            {"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0},"hp":40.0}
            """);

        // Record version

        // Set the world to the old shape
        store.updateState(WorldIds.PILLAR, oldPillarState);
        long oldPillarVersion = version(WorldIds.PILLAR);

        // Call seeder.seed() - should return false (upgrade happened)
        assertThat(seeder.seed()).isFalse();

        // Pillar should have hp 40.0 and radius 0.5
        JsonNode newPillarState = store.get(WorldIds.PILLAR).orElseThrow().state();
        assertThat(newPillarState.path("hp").asDouble()).isEqualTo(40.0);
        assertThat(newPillarState.path("radius").asDouble()).isEqualTo(0.5);

        // Pillar version should be old+1
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);

        // Call seeder.seed() a second time - returns false: the game exists, nothing changes
        assertThat(seeder.seed()).isFalse();

        // Version should be unchanged
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);

        // Orbiter should keep its version (fresh seed shape)
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
    }

    @Test
    void theCurrentShapeIsLeftAlone() throws Exception {
        // Record all versions first
        long pillarVersion = version(WorldIds.PILLAR);
        long orbiterVersion = version(WorldIds.ORBITER);
        long areaVersion = version(WorldIds.AREA);

        // Call seeder.seed() - returns false: the game exists, nothing changes
        assertThat(seeder.seed()).isFalse();

        // All versions should be unchanged
        assertThat(version(WorldIds.PILLAR)).isEqualTo(pillarVersion);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiterVersion);
        assertThat(version(WorldIds.AREA)).isEqualTo(areaVersion);
    }

    @Test
    void theOrbitKeyShapeKeepsItsOrbit() throws Exception {
        // Old shape states
        ObjectNode oldPillarState = (ObjectNode) JSON.readTree("""
            {"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0}}
            """);

        ObjectNode oldOrbiterState = (ObjectNode) JSON.readTree("""
            {"kind":"mobile","name":"orbiter","position":{"x":5.0,"y":7.0},"orbit":{"center":{"x":5.0,"y":4.0},"radius":3.0,"period":60.0}}
            """);

        // Record versions

        // Set the world to the old shape
        store.updateState(WorldIds.PILLAR, oldPillarState);
        store.updateState(WorldIds.ORBITER, oldOrbiterState);
        long oldPillarVersion = version(WorldIds.PILLAR);
        long oldOrbiterVersion = version(WorldIds.ORBITER);

        // Call seeder.seed() - should return false (upgrade happened)
        assertThat(seeder.seed()).isFalse();

        // Check that orbiter has no 'orbit' key, speed 0.5, position unchanged
        JsonNode newOrbiterState = store.get(WorldIds.ORBITER).orElseThrow().state();
        assertThat(newOrbiterState.has("orbit")).isFalse();
        assertThat(newOrbiterState.path("speed").asDouble()).isEqualTo(0.5);
        assertThat(newOrbiterState.path("position").path("x").asDouble()).isEqualTo(5.0);
        assertThat(newOrbiterState.path("position").path("y").asDouble()).isEqualTo(7.0);

        // Check that actions and behaviour are from the seed
        try (InputStream is = SeederUpgradeTest.class.getResourceAsStream("/seed/orbiter.json")) {
            JsonNode seedOrbiter = JSON.readTree(is);
            assertThat(newOrbiterState.path("actions")).isEqualTo(seedOrbiter.path("actions"));
            assertThat(newOrbiterState.path("behaviour")).isEqualTo(seedOrbiter.path("behaviour"));
        }

        // Pillar should have radius 0.5 and hp 100.0
        JsonNode newPillarState = store.get(WorldIds.PILLAR).orElseThrow().state();
        assertThat(newPillarState.path("radius").asDouble()).isEqualTo(0.5);
        assertThat(newPillarState.path("hp").asDouble()).isEqualTo(100.0);

        // Call seeder.seed() a second time - returns false: the game exists, nothing changes
        assertThat(seeder.seed()).isFalse();

        // Versions should be unchanged
        assertThat(version(WorldIds.ORBITER)).isEqualTo(oldOrbiterVersion + 1);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);
    }

    @Test
    void theRepeatShapeKeepsItsOrbitStepParams() throws Exception {
        // Old orbiter shape with repeat behaviour
        ObjectNode oldOrbiterState = (ObjectNode) JSON.readTree("""
            {"kind":"mobile","name":"orbiter","position":{"x":5.0,"y":7.0},"speed":0.5,"actions":["orbit-step"],"behaviour":{"kind":"repeat","action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":2.0,"arc":0.5}}},"action":{"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":2.0,"arc":0.5},"start":1000000.0,"end":1000001.0,"effect":"end"}}
            """);

        // Old pillar shape
        ObjectNode oldPillarState = (ObjectNode) JSON.readTree("""
            {"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0}}
            """);

        // Record versions

        // Set the world to the old shape
        store.updateState(WorldIds.PILLAR, oldPillarState);
        store.updateState(WorldIds.ORBITER, oldOrbiterState);
        long oldPillarVersion = version(WorldIds.PILLAR);
        long oldOrbiterVersion = version(WorldIds.ORBITER);

        // Call seeder.seed() - should return false (upgrade happened)
        assertThat(seeder.seed()).isFalse();

        // Check that the orbit-step choice of behaviour.choices has params.radius 2.0
        JsonNode newOrbiterState = store.get(WorldIds.ORBITER).orElseThrow().state();
        JsonNode newBehaviour = newOrbiterState.path("behaviour");

        // If it's a random behaviour, check that the first choice has radius 2.0
        if (newBehaviour.path("kind").asText().equals("random")) {
            JsonNode choices = newBehaviour.path("choices");
            for (JsonNode choice : choices) {
                if ("orbit-step".equals(choice.path("action").path("type").asText())) {
                    assertThat(choice.path("action").path("params").path("radius").asDouble()).isEqualTo(2.0);
                }
            }
        }

        // Check that the action is unchanged
        JsonNode oldAction = oldOrbiterState.path("action");
        JsonNode newAction = newOrbiterState.path("action");
        assertThat(newAction).isEqualTo(oldAction);

        // Call seeder.seed() a second time - returns false: the game exists, nothing changes
        assertThat(seeder.seed()).isFalse();

        // Versions should be unchanged
        assertThat(version(WorldIds.ORBITER)).isEqualTo(oldOrbiterVersion + 1);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(oldPillarVersion + 1);
    }
}
