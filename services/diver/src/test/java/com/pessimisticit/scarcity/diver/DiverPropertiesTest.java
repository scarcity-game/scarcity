package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.ControllerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml's {@code controller} and {@code diver} blocks, bound and checked by Bean Validation. */
class DiverPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ControllerProperties.class, DiverProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner;

    DiverPropertiesTest() {
        this.runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class);
    }

    @Test
    void theDefaultsPlayEveryLevelWithAThirtySecondPause() {
        runner.withPropertyValues("controller.api-key=" + Fixtures.KEY).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ControllerProperties props = ctx.getBean(ControllerProperties.class);
            assertThat(props.principal()).isEqualTo("diver");
            assertThat(props.feed()).as("no area: the turn feed").isTrue();
            assertThat(props.think()).isEqualTo(Duration.ZERO);
            assertThat(ctx.getBean(DiverProperties.class).arrivalPause()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.weights()).containsOnlyKeys("step", "traverse", "wait");
            DiverProperties diver = ctx.getBean(DiverProperties.class);
            assertThat(diver.stepSettings().maxDistance()).isEqualTo(1.0);
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
            {"diver.step.max-distance=0", "stepSettings.maxDistance"},
            {"diver.wait.duration=-1", "waitSettings.duration"},
            {"diver.arrival-pause=-1s", "arrivalPause"},
            {"controller.area=entity:x", "area"},
            {"controller.weights.step=-1", "weights[step]"},
        };
        for (String[] b : bad) {
            runner.withPropertyValues("controller.api-key=k", b[0]).run(ctx ->
                    assertThat(ctx).as(b[0]).getFailure().rootCause().hasMessageContaining(b[1]));
        }
    }
}
