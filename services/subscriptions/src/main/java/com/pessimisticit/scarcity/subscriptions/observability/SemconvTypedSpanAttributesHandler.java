package com.pessimisticit.scarcity.subscriptions.observability;

import java.net.URI;

import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sets the integer-typed semconv attributes on HTTP server and client spans:
 * {@code http.response.status_code} and {@code server.port}.
 *
 * <p>Micrometer key-values are strings, and the tracing handler copies them onto the span
 * as string attributes. TraceQL compares types strictly, so
 * {@code {span.http.response.status_code = 401}} only matches an int attribute. This
 * handler tags the span directly with {@link Span#tag(String, long)}, which the OTel
 * bridge turns into a LONG attribute.
 *
 * <p>Ordering: Boot registers this (uncategorised) handler after its tracing and metrics
 * handler groups, and Micrometer calls {@code onStop} in reverse registration order, so
 * this runs while the span is still open, before the tracing handler ends it. Without
 * tracing (no span in the context) it does nothing. It never touches metrics.
 */
public class SemconvTypedSpanAttributesHandler implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext
                || context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onStop(Observation.Context context) {
        TracingContext tracing = context.get(TracingContext.class);
        Span span = tracing == null ? null : tracing.getSpan();
        if (span == null) {
            return;
        }
        if (context instanceof ServerRequestObservationContext server) {
            HttpServletResponse response = server.getResponse();
            if (response != null && response.getStatus() > 0) {
                span.tag("http.response.status_code", (long) response.getStatus());
            }
            int port = server.getCarrier().getServerPort();
            if (port > 0) {
                span.tag("server.port", (long) port);
            }
        } else if (context instanceof ClientRequestObservationContext client) {
            Integer status = SemconvClientRequestObservationConvention.statusCode(client);
            if (status != null) {
                span.tag("http.response.status_code", (long) status);
            }
            ClientHttpRequest request = client.getCarrier();
            if (request != null) {
                URI uri = request.getURI();
                int port = HttpSemconv.port(uri);
                if (port > 0) {
                    span.tag("server.port", (long) port);
                }
            }
        }
    }
}
