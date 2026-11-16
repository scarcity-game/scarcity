package com.pessimisticit.scarcity.observability;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;

/**
 * The integer-typed semconv attributes, {@code http.response.status_code} and
 * {@code server.port}, set on the open span of an HTTP observation.
 *
 * <p>Micrometer key-values are strings, and the tracing handler copies them onto the span
 * as string attributes. TraceQL compares types strictly, so
 * {@code {span.http.response.status_code = 401}} only matches an int attribute. The
 * handlers tag the span directly with {@link Span#tag(String, long)}, which the OTel
 * bridge turns into a LONG attribute.
 *
 * <p>Ordering: Boot registers the (uncategorised) handlers after its tracing and metrics
 * handler groups, and Micrometer calls {@code onStop} in reverse registration order, so
 * they run while the span is still open, before the tracing handler ends it. Without
 * tracing (no span in the context) they do nothing. They never touch metrics.
 */
final class TypedSpanAttributes {

    private TypedSpanAttributes() {
    }

    /**
     * The open span, or null. The tracing handler owns its TracingContext, so it isn't held
     * in a local here (PMD's CloseResource).
     */
    static Span span(Observation.Context context) {
        return context.get(TracingContext.class) == null ? null : context.<TracingContext>get(TracingContext.class).getSpan();
    }

    /** Tags {@code status} and {@code port} where they are known (positive). */
    static void tag(Span span, Integer status, int port) {
        if (status != null && status > 0) {
            span.tag("http.response.status_code", (long) status);
        }
        if (port > 0) {
            span.tag("server.port", (long) port);
        }
    }
}
