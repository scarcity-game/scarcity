package com.pessimisticit.scarcity.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link Ticker} on a single scheduler thread (Spring's default scheduler has one),
 * a fixed delay after the previous tick ended. A failed tick is logged and the loop goes on.
 * Switch off with {@code scarcity.world.tick.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "scarcity.world.tick", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
public class TickLoop {

    private static final Logger log = LoggerFactory.getLogger(TickLoop.class);

    private final Ticker ticker;

    TickLoop(Ticker ticker) {
        this.ticker = ticker;
    }

    @Scheduled(fixedDelayString = "${scarcity.world.tick.delay-millis:200}")
    void run() {
        try {
            ticker.tick();
        } catch (RuntimeException e) {
            log.error("tick failed", e);
        }
    }
}
