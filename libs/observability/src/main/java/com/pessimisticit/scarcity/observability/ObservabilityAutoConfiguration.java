package com.pessimisticit.scarcity.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import net.ttddyy.observation.tracing.QueryObservationConvention;

/**
 * The services' shared observability wiring. Everything else (OTel bridge, OTLP exporter,
 * Prometheus, JDBC spans, log correlation) is Spring Boot / datasource-micrometer
 * auto-configuration, driven by {@code scarcity-observability.yml}, which a service imports.
 *
 * <ul>
 * <li>Always: semconv attributes on HTTP client spans (RestClient and RestTemplate built
 * from Boot's builders).</li>
 * <li>Servlet web apps: semconv on HTTP server spans, and no observations of actuator
 * requests.</li>
 * <li>With datasource-micrometer on the classpath: semconv on JDBC query spans, and JDBC
 * observations only inside another observation.</li>
 * </ul>
 * Each bean backs off when the service defines its own.
 */
@AutoConfiguration(after = WebEndpointAutoConfiguration.class)
public class ObservabilityAutoConfiguration {

    /**
     * OTel semconv attributes on HTTP client spans; metric tags unchanged. Boot's
     * RestClient/RestTemplate observation configuration uses a
     * {@code ClientRequestObservationConvention} bean in place of its default.
     */
    @Bean
    @ConditionalOnMissingBean
    SemconvClientRequestObservationConvention semconvClientRequestObservationConvention(
            ObservationProperties observations) {
        return new SemconvClientRequestObservationConvention(
                observations.getHttp().getClient().getRequests().getName());
    }

    /** Integer-typed {@code http.response.status_code} and {@code server.port} on HTTP client spans. */
    @Bean
    @ConditionalOnMissingBean
    SemconvClientSpanAttributesHandler semconvClientSpanAttributesHandler() {
        return new SemconvClientSpanAttributesHandler();
    }

    /** HTTP server spans: semconv attributes and no actuator requests. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class Server {

        /**
         * Drops HTTP server observations (spans and {@code http.server.requests} metrics) for
         * actuator requests. Prometheus scrapes and kube probes would otherwise flood the traces.
         * Matches on the actuator base path ({@code management.endpoints.web.base-path}). With
         * actuator on its own management port they aren't observed at all; this covers a
         * service where it shares the server port.
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
        @ConditionalOnMissingBean
        SemconvServerRequestObservationConvention semconvServerRequestObservationConvention(
                ObservationProperties observations) {
            return new SemconvServerRequestObservationConvention(
                    observations.getHttp().getServer().getRequests().getName());
        }

        /** Integer-typed {@code http.response.status_code} and {@code server.port} on HTTP server spans. */
        @Bean
        @ConditionalOnMissingBean
        SemconvServerSpanAttributesHandler semconvServerSpanAttributesHandler() {
            return new SemconvServerSpanAttributesHandler();
        }
    }

    /** JDBC query spans (datasource-micrometer). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(QueryObservationConvention.class)
    public static class Jdbc {

        /**
         * {@code db.system} and {@code db.query.text} on JDBC query spans. The database system
         * comes from the JDBC URL of Boot's (single) datasource connection details.
         */
        @Bean
        @ConditionalOnMissingBean
        SemconvQueryObservationConvention semconvQueryObservationConvention(
                ObjectProvider<JdbcConnectionDetails> jdbc) {
            JdbcConnectionDetails details = jdbc.getIfAvailable();
            return new SemconvQueryObservationConvention(
                    SemconvQueryObservationConvention.dbSystemFromJdbcUrl(details == null ? null : details.getJdbcUrl()));
        }

        /**
         * JDBC observations ({@code jdbc.*}) are only kept when they happen inside another
         * observation, e.g. an HTTP request. That drops the parentless root traces that startup
         * work (Flyway migrations) and pool housekeeping would otherwise create, one trace per
         * statement.
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
}
