package com.pessimisticit.scarcity.auth.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

/**
 * The predicates on their own. The integration test shows actuator on the separate
 * management port isn't observed at all; this covers the predicate that still drops it
 * when actuator shares the server port (management.server.port unset).
 */
class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void actuatorServerRequestsAreNotObserved() {
        ObservationPredicate predicate = config.noActuatorServerObservations(new WebEndpointProperties());
        assertThat(predicate.test("http.server.requests", server("/actuator/health"))).isFalse();
        assertThat(predicate.test("http.server.requests", server("/actuator/health/readiness"))).isFalse();
        assertThat(predicate.test("http.server.requests", server("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", server("/actuator"))).isFalse();

        assertThat(predicate.test("http.server.requests", server("/api/auth/login"))).isTrue();
        assertThat(predicate.test("http.server.requests", server("/actuatorish"))).isTrue();
        assertThat(predicate.test("jdbc.query", new Observation.Context())).isTrue();
    }

    @Test
    void customActuatorBasePathIsHonoured() {
        WebEndpointProperties props = new WebEndpointProperties();
        props.setBasePath("/manage");
        ObservationPredicate predicate = config.noActuatorServerObservations(props);
        assertThat(predicate.test("http.server.requests", server("/manage/health"))).isFalse();
        assertThat(predicate.test("http.server.requests", server("/actuator/health"))).isTrue();
    }

    @Test
    void jdbcObservationsNeedAnEnclosingObservation() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        config.jdbcOnlyInsideAnotherObservation().customize(registry);

        assertThat(Observation.createNotStarted("jdbc.query", registry).isNoop()).isTrue();
        assertThat(Observation.createNotStarted("other", registry).isNoop()).isFalse();

        Observation request = Observation.start("http.server.requests", registry);
        try (Observation.Scope scope = request.openScope()) {
            assertThat(Observation.createNotStarted("jdbc.query", registry).isNoop()).isFalse();
        }
        finally {
            request.stop();
        }
    }

    private static ServerRequestObservationContext server(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
