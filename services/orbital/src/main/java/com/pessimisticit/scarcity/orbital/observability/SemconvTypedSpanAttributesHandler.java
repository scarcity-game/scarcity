package com.pessimisticit.scarcity.orbital.observability;

import java.net.URI;

import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;

/**
 * Sets the integer-typed semconv attributes on HTTP client spans:
 * {@code http.response.status_code} and {@code server.port}. services/world's handler,
 * client side only: orbital serves nothing.
 *
 * <p>Micrometer key-values are strings, and the tracing handler copies them onto the span
 * as string attributes. TraceQL compares types strictly, so
 * {@code {span.http.response.status_code = 401}} only matches an int attribute. This
 * handler tags the span directly with {@link Span#tag(String, long)}, which the OTel
 * bridge turns into a LONG attribute.
 *
 * <p>Ordering: Boot registers this (uncategorised) handler after its tracing and metrics
 * handler groups, and Micrometer calls {@code onStop} in reverse registration order, so
 * this runs while the span is still open. Without tracing it does nothing.
 */
public class SemconvTypedSpanAttributesHandler implements ObservationHandler<ClientRequestObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        Span span = span(context);
        if (span == null) {
            return;
        }
        Integer status = SemconvClientRequestObservationConvention.statusCode(context);
        if (status != null) {
            span.tag("http.response.status_code", (long) status);
        }
        int port = port(context.getCarrier());
        if (port > 0) {
            span.tag("server.port", (long) port);
        }
    }

    /** The open span; the tracing handler owns its TracingContext, so it isn't held here (PMD's CloseResource). */
    private static Span span(ClientRequestObservationContext context) {
        return context.get(TracingContext.class) == null ? null : context.<TracingContext>get(TracingContext.class).getSpan();
    }

    private static int port(ClientHttpRequest request) {
        if (request == null) {
            return -1;
        }
        URI uri = request.getURI();
        return HttpSemconv.port(uri);
    }
}
