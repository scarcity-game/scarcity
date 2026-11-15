package com.pessimisticit.scarcity.orbital.observability;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/**
 * Service-agnostic observability wiring, copied from services/world without the JDBC
 * parts (orbital has no database) and the server-span convention (8080 serves nothing). Everything else (OTel bridge, OTLP exporter,
 * Prometheus, log correlation) is Spring Boot auto-configuration driven by
 * {@code observability.yml}.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Drops HTTP server observations (spans and {@code http.server.requests} metrics) for
     * actuator requests. Prometheus scrapes and kube probes would otherwise flood the traces.
     */
    @Bean
    ObservationPredicate noActuatorServerObservations(WebEndpointProperties webEndpoints) {
        String basePath = webEndpoints.getBasePath();
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server) {
                String uri = server.getCarrier().getRequestURI();
                return !(uri.equals(basePath) || uri.startsWith(basePath + "/"));
            }
            return true;
        };
    }

    /**
     * OTel semconv attributes on HTTP client spans (RestClient built from Boot's builder,
     * which is how orbital calls world); metric tags unchanged.
     */
    @Bean
    SemconvClientRequestObservationConvention semconvClientRequestObservationConvention(
            ObservationProperties observations) {
        return new SemconvClientRequestObservationConvention(
                observations.getHttp().getClient().getRequests().getName());
    }

    /** Integer-typed {@code http.response.status_code} and {@code server.port} on HTTP spans. */
    @Bean
    SemconvTypedSpanAttributesHandler semconvTypedSpanAttributesHandler() {
        return new SemconvTypedSpanAttributesHandler();
    }
}
