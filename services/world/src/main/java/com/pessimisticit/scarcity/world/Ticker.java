package com.pessimisticit.scarcity.world;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.resourcemodel.WriteResult;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * One tick: moves the orbiter to where the wall clock says it is and publishes one change
 * batch for the area. The position is a pure function of time (counter-clockwise, angle =
 * 2*pi*(epochMillis mod periodMillis)/periodMillis), so it survives restarts. A tick is one
 * transaction: write and publish commit together (or not at all).
 */
@Component
public class Ticker {

    private static final Logger log = LoggerFactory.getLogger(Ticker.class);

    private final ResourceStore store;
    private final ChangePublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Tracer tracer;

    public Ticker(ResourceStore store, ChangePublisher publisher, TransactionTemplate tx, Clock clock,
            Tracer tracer) {
        this.store = store;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.tracer = tracer;
    }

    /** Runs one tick inside a {@code world.tick} span. Exceptions propagate to the caller. */
    public void tick() {
        Span span = tracer.nextSpan().name("world.tick").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            tx.executeWithoutResult(status -> doTick());
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void doTick() {
        Resource orbiter = store.get(WorldIds.ORBITER).orElse(null);
        if (orbiter == null) {
            log.debug("tick skipped: {} not seeded yet", WorldIds.ORBITER);
            return;
        }
        ObjectNode state = orbiter.state().deepCopy();
        JsonNode orbit = state.path("orbit");
        double[] pos = position(orbit, clock.millis());
        ObjectNode position = state.putObject("position");
        position.put("x", pos[0]);
        position.put("y", pos[1]);
        WriteResult w = store.updateState(orbiter.id(), state);
        publisher.publish(new ChangeBatch(w.areaId(),
                List.of(new ChangeBatch.Change(w.id(), ResourceType.ENTITY, w.version(), state)), null));
    }

    /** {x, y} of a body on {@code orbit} ({center, radius, period in seconds}) at {@code epochMillis}. */
    static double[] position(JsonNode orbit, long epochMillis) {
        long periodMillis = Math.round(orbit.path("period").asDouble() * 1000.0);
        double angle = 2 * Math.PI * Math.floorMod(epochMillis, periodMillis) / periodMillis;
        double r = orbit.path("radius").asDouble();
        double cx = orbit.path("center").path("x").asDouble();
        double cy = orbit.path("center").path("y").asDouble();
        return new double[] {cx + r * Math.cos(angle), cy + r * Math.sin(angle)};
    }
}
