package com.pessimisticit.scarcity.resourcemodel;

import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * The only class that touches micrometer-tracing (an optional dependency). Never load it
 * unless {@code io.micrometer.tracing.Tracer} is on the classpath.
 */
public final class MicrometerTracing {

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private MicrometerTracing() {
    }

    /** {@code 00-<traceId>-<spanId>-01} of the current span; null when there is none. */
    public static TraceparentSource source(Tracer tracer) {
        return () -> {
            Span span = tracer.currentSpan();
            if (span == null) {
                return null;
            }
            TraceContext ctx = span.context();
            return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-01";
        };
    }

    /**
     * Runs {@code work} inside a new span named {@code name} that is a child of the batch's
     * traceparent (or a root span if absent or malformed), so a consumer continues the trace.
     */
    public static <T> T inChildSpan(Tracer tracer, ChangeBatch batch, String name, Callable<T> work)
            throws Exception {
        Span.Builder builder = tracer.spanBuilder().name(name);
        String tp = batch.traceparent();
        Matcher m = tp == null ? null : TRACEPARENT.matcher(tp);
        if (m != null && m.matches()) {
            builder.setParent(tracer.traceContextBuilder().traceId(m.group(1)).spanId(m.group(2))
                    .sampled(true).build());
        }
        Span span = builder.start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.call();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
