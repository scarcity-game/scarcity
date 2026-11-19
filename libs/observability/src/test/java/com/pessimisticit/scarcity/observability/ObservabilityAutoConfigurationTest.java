package com.pessimisticit.scarcity.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.ttddyy.observation.tracing.QueryObservationConvention;

/** Which parts apply where: client always, server in servlet apps, JDBC with datasource-micrometer. */
class ObservabilityAutoConfigurationTest {

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(
            ObservationAutoConfiguration.class, EndpointAutoConfiguration.class,
            WebEndpointAutoConfiguration.class, ObservabilityAutoConfiguration.class);

    @Test
    void aServletAppGetsServerClientAndJdbcParts() {
        new WebApplicationContextRunner().withConfiguration(CONFIGS).run(context -> {
            assertThat(context).hasSingleBean(SemconvClientRequestObservationConvention.class);
            assertThat(context).hasSingleBean(SemconvClientSpanAttributesHandler.class);
            assertThat(context).hasSingleBean(SemconvServerRequestObservationConvention.class);
            assertThat(context).hasSingleBean(SemconvServerSpanAttributesHandler.class);
            assertThat(context).hasBean("noActuatorServerObservations");
            assertThat(context).hasSingleBean(SemconvQueryObservationConvention.class);
            assertThat(context).hasBean("jdbcOnlyInsideAnotherObservation");
        });
    }

    @Test
    void aNonWebAppWithoutJdbcGetsOnlyTheClientParts() {
        new ApplicationContextRunner().withConfiguration(CONFIGS)
                .withClassLoader(new FilteredClassLoader(QueryObservationConvention.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(SemconvClientRequestObservationConvention.class);
                    assertThat(context).hasSingleBean(SemconvClientSpanAttributesHandler.class);
                    assertThat(context).doesNotHaveBean(SemconvServerRequestObservationConvention.class);
                    assertThat(context).doesNotHaveBean(SemconvServerSpanAttributesHandler.class);
                    assertThat(context).doesNotHaveBean("noActuatorServerObservations");
                    assertThat(context).doesNotHaveBean(SemconvQueryObservationConvention.class);
                });
    }

    @Test
    void aServiceConventionReplacesTheLibrarysOwn() {
        new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withUserConfiguration(OwnConvention.class)
                .run(context -> assertThat(context.getBean(SemconvClientRequestObservationConvention.class))
                        .isSameAs(context.getBean("own")));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnConvention {
        @Bean
        SemconvClientRequestObservationConvention own() {
            return new SemconvClientRequestObservationConvention("custom");
        }
    }
}
