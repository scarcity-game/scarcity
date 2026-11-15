package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.engine.IntentRejected;
import com.pessimisticit.scarcity.world.model.Controller;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one intent as one advance in one transaction, inside a
 * {@code world.advance} span (docs/actions.md, "Intents"). A rejection rolls
 * the transaction back: nothing is written.
 */
@Component
public class Intents {

    private final AreaAdvance areaAdvance;
    private final TransactionTemplate tx;
    private final Tracer tracer;

    Intents(AreaAdvance areaAdvance, TransactionTemplate tx, Tracer tracer) {
        this.areaAdvance = areaAdvance;
        this.tx = tx;
        this.tracer = tracer;
    }

    /**
     * The 200 body: the area's new time and log seq, and the entity's version
     * after the advance (null when the advance destroyed it).
     */
    public record Advanced(String area, double time, long logSeq, Long version) {
    }

    /**
     * @throws IntentRejected if the intent can't start; nothing was written
     */
    public Advanced post(Controller caller, IntentRequest request) {
        Span span = tracer.nextSpan().name("world.advance").tag("scarcity.entity", request.entity()).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return tx.execute(s -> areaAdvance.run(caller, request));
        } catch (IntentRejected e) {
            span.tag("scarcity.rejected", e.reason().name());
            throw e;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
