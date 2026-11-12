package com.pessimisticit.scarcity.world.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

/**
 * Service-agnostic observability wiring. Everything else (OTel bridge, OTLP exporter,
 * Prometheus, JDBC spans, log correlation) is Spring Boot / datasource-micrometer
 * auto-configuration driven by the "observability" block in {@code application.yml}.
 *
 * <p>This package has no knowledge of the service it lives in; copy it as-is to the next
 * Java service (and rename the package).
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Drops HTTP server observations (spans and {@code http.server.requests} metrics) for
     * actuator requests. Prometheus scrapes and kube probes would otherwise flood the traces.
     * Matches on the actuator base path ({@code management.endpoints.web.base-path}).
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
     * OTel semconv attributes on HTTP server spans; metric tags unchanged. Boot's
     * WebMvcObservationAutoConfiguration uses a {@code ServerRequestObservationConvention}
     * bean in place of its default.
     */
    @Bean
    SemconvServerRequestObservationConvention semconvServerRequestObservationConvention(
            ObservationProperties observations) {
        return new SemconvServerRequestObservationConvention(
                observations.getHttp().getServer().getRequests().getName());
    }

    /**
     * OTel semconv attributes on HTTP client spans (RestClient and RestTemplate built from
     * Boot's builders); metric tags unchanged.
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

    /**
     * {@code db.system} and {@code db.query.text} on JDBC query spans. The database system
     * comes from the JDBC URL of Boot's (single) datasource connection details.
     */
    @Bean
    SemconvQueryObservationConvention semconvQueryObservationConvention(
            ObjectProvider<JdbcConnectionDetails> jdbc) {
        JdbcConnectionDetails details = jdbc.getIfAvailable();
        return new SemconvQueryObservationConvention(
                SemconvQueryObservationConvention.dbSystemFromJdbcUrl(details == null ? null : details.getJdbcUrl()));
    }

    /**
     * JDBC observations ({@code jdbc.*}, from datasource-micrometer) are only kept when
     * they happen inside another observation, e.g. an HTTP request. That drops the
     * parentless root traces that startup work (Flyway migrations) and pool housekeeping
     * would otherwise create, one trace per statement.
     *
     * <p>A customizer rather than an {@link ObservationPredicate} bean, because the
     * predicate needs the registry it is registered on.
     */
    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> jdbcOnlyInsideAnotherObservation() {
        return registry -> registry.observationConfig().observationPredicate(
                (name, context) -> !name.startsWith("jdbc.") || registry.getCurrentObservation() != null);
    }
}
