package com.pessimisticit.scarcity.observability;

import org.springframework.http.client.observation.ClientRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;

/** {@link TypedSpanAttributes} on HTTP client spans: the response status and the server's port. */
public class SemconvClientSpanAttributesHandler implements ObservationHandler<ClientRequestObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        Span span = TypedSpanAttributes.span(context);
        if (span == null) {
            return;
        }
        int port = context.getCarrier() == null ? -1 : HttpSemconv.port(context.getCarrier().getURI());
        TypedSpanAttributes.tag(span, SemconvClientRequestObservationConvention.statusCode(context), port);
    }
}
