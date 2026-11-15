package com.pessimisticit.scarcity.observability;

import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;

/** {@link TypedSpanAttributes} on HTTP server spans: the response status and the local port. */
public class SemconvServerSpanAttributesHandler implements ObservationHandler<ServerRequestObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        Span span = TypedSpanAttributes.span(context);
        if (span == null) {
            return;
        }
        Integer status = context.getResponse() == null ? null : context.getResponse().getStatus();
        TypedSpanAttributes.tag(span, status, context.getCarrier().getServerPort());
    }
}
