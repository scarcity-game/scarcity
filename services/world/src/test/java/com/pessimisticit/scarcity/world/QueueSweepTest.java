package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import com.pessimisticit.scarcity.world.model.Area;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An area stored with no queue gets one at startup (#158). */
class QueueSweepTest extends WorldTest {

    @Autowired
    QueueSweep queueSweep;

    @Test
    void anAreaWithNoUpGetsItsQueueWrittenAndLogged() throws Exception {
        Area emptied = area().withQueue(List.of());
        tx.executeWithoutResult(s -> store.update(emptied));

        assertThat(queueSweep.run()).isEqualTo(1);

        assertThat(area().activeEntity().entity()).isEqualTo(WorldIds.ORBITER);
        JsonNode log = json(get(port, "/api/world/log?area=" + WorldIds.AREA + "&kind=queue", TestSupport.validToken()));
        assertThat(log.path("entries")).hasSize(1);
        assertThat(queueSweep.run()).as("a right queue is left alone").isZero();
    }
}
