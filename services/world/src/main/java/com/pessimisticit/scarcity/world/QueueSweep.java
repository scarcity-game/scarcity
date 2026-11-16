package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At startup, writes the queue into every area that lacks the right one, so a controlled idle
 * entity added outside an advance (or an area stored before turns) can never leave an area
 * with nobody up (docs/actions.md, "Turns").
 */
@Component
class QueueSweep {

    private static final Logger log = LoggerFactory.getLogger(QueueSweep.class);

    private final ResourceStore resourceStore;
    private final AreaAdvance areaAdvance;
    private final TransactionTemplate tx;

    QueueSweep(ResourceStore resourceStore, AreaAdvance areaAdvance, TransactionTemplate tx) {
        this.resourceStore = resourceStore;
        this.areaAdvance = areaAdvance;
        this.tx = tx;
    }

    /** Refreshes each area in its own transaction; returns how many it wrote. */
    int run() {
        int written = 0;
        int page = 0;
        Page<Resource> areas;
        do {
            areas = resourceStore.list(ResourceType.AREA, Map.of(), page, ResourceStore.MAX_SIZE);
            for (Resource area : areas.items()) {
                if (Boolean.TRUE.equals(tx.execute(s -> areaAdvance.refreshQueue(area.id())))) {
                    log.info("{}: wrote the queue", area.id());
                    written++;
                }
            }
            page++;
        } while ((long) page * ResourceStore.MAX_SIZE < areas.total());
        return written;
    }
}
