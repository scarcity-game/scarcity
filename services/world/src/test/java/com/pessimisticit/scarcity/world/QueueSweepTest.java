package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An area stored with no queue gets one at startup (#158). */
class QueueSweepTest extends WorldTest {

    @Autowired
    QueueSweep queueSweep;

    @Test
    void anAreaWithNoUpGetsItsQueueWrittenAndLogged() throws Exception {
        ObjectNode state = resource(WorldIds.AREA).state().deepCopy();
        state.remove("activeEntity");
        state.remove("pending");
        tx.executeWithoutResult(s -> store.updateState(WorldIds.AREA, state));

        assertThat(queueSweep.run()).isEqualTo(1);

        assertThat(resource(WorldIds.AREA).state().path("activeEntity").path("entity").asText()).isEqualTo(WorldIds.ORBITER);
        JsonNode log = json(get(port, "/api/world/log?area=" + WorldIds.AREA + "&kind=queue", TestSupport.validToken()));
        assertThat(log.path("entries")).hasSize(1);
        assertThat(queueSweep.run()).as("a right queue is left alone").isZero();
    }
}
