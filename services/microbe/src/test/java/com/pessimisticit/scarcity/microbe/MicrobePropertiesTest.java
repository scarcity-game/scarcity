package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.CommonProperties;
import com.pessimisticit.scarcity.controller.EndpointProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** application.yml's {@code controller} and {@code microbe} blocks, bound and checked by Bean Validation. */
class MicrobePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CommonProperties.class, EndpointProperties.class, MicrobeProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner;

    MicrobePropertiesTest() {
        this.runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Props.class);
    }

    @Test
    void theDefaultsAreAnEndpointOfTheMice() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CommonProperties props = ctx.getBean(CommonProperties.class);
            assertThat(props.principal()).isEqualTo("microbe");
            assertThat(props.mode()).isEqualTo(CommonProperties.Mode.ENDPOINT);
            assertThat(props.weights()).containsOnlyKeys("step", "wait");
            assertThat(ctx.getBean(EndpointProperties.class).jwkSetUri().toString()).endsWith("/jwks.json");
            MicrobeProperties microbe = ctx.getBean(MicrobeProperties.class);
            assertThat(microbe.stepSettings().distance()).isEqualTo(1.0);
        });
    }

    @Test
    void badValuesFailStartupNamingTheirKey() {
        String[][] bad = {
            {"microbe.step.distance=0", "stepSettings.distance"},
            {"microbe.wait.duration=-1", "waitSettings.duration"},
            {"controller.weights.step=-1", "weights[step]"},
            {"controller.fallback-wait=0", "fallbackWait"},
        };
        for (String[] b : bad) {
            runner.withPropertyValues(b[0]).run(ctx ->
                    assertThat(ctx).as(b[0]).getFailure().rootCause().hasMessageContaining(b[1]));
        }
    }
}
