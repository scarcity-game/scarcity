package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.ControllerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml's {@code controller} and {@code microbe} blocks, bound and checked by Bean Validation. */
class MicrobePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ControllerProperties.class, MicrobeProperties.class})
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
    void theDefaultsPlayEveryAreaWithNoPause() {
        runner.withPropertyValues("controller.api-key=" + Fixtures.KEY).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ControllerProperties props = ctx.getBean(ControllerProperties.class);
            assertThat(props.principal()).isEqualTo("microbe");
            assertThat(props.feed()).as("no area: the turn feed").isTrue();
            assertThat(props.think()).isEqualTo(Duration.ZERO);
            assertThat(props.weights()).containsOnlyKeys("step", "wait");
            MicrobeProperties microbe = ctx.getBean(MicrobeProperties.class);
            assertThat(microbe.stepSettings().distance()).isEqualTo(1.0);
            assertThat(props.toString()).doesNotContain(Fixtures.KEY);
        });
    }

    @Test
    void aMissingKeyFailsStartup() {
        runner.run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("apiKey"));
    }

    @Test
    void badValuesFailStartupNamingTheirKey() {
        String[][] bad = {
            {"microbe.step.distance=0", "stepSettings.distance"},
            {"microbe.wait.duration=-1", "waitSettings.duration"},
            {"controller.area=entity:x", "area"},
            {"controller.weights.step=-1", "weights[step]"},
        };
        for (String[] b : bad) {
            runner.withPropertyValues("controller.api-key=k", b[0]).run(ctx ->
                    assertThat(ctx).as(b[0]).getFailure().rootCause().hasMessageContaining(b[1]));
        }
    }
}
