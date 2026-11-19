package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.ControllerProperties;
import com.pessimisticit.scarcity.controller.StubWorld;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml's {@code controller} and {@code orbital} blocks, bound and checked by Bean Validation. */
class OrbitalPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ControllerProperties.class, OrbitalProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner;

    OrbitalPropertiesTest() {
        this.runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class);
    }

    @Test
    void theDefaultsBindWithAKey() {
        runner.withPropertyValues("controller.api-key=" + Fixtures.KEY).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ControllerProperties props = ctx.getBean(ControllerProperties.class);
            assertThat(props.principal()).isEqualTo("orbital");
            assertThat(ctx.getBean(OrbitalProperties.class).waitSettings().duration()).isEqualTo(1.0);
            assertThat(props.think()).isEqualTo(Duration.ofSeconds(1));
            assertThat(props.area()).isEqualTo(StubWorld.AREA);
            assertThat(props.subscriptionsUrl().toString()).isEqualTo("ws://localhost:8080/api/subscriptions/ws");
            assertThat(props.toString()).contains("apiKey=<hidden>").doesNotContain(Fixtures.KEY);
        });
    }

    @Test
    void aMissingKeyFailsStartup() {
        runner.run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("apiKey"));
    }

    @Test
    void badValuesFailStartupNamingTheirKey() {
        String[][] bad = {
            {"controller.weights.step=-1", "weights[step]"},
            {"orbital.step.radius=0", "stepSettings.radius"},
            {"orbital.wait.duration=-1", "waitSettings.duration"},
            {"orbital.throw.spread=-0.1", "throwSettings.spread"},
            {"orbital.throw.recovery=0", "throwSettings.recovery"},
            {"orbital.throw.target=pillar", "throwSettings.target"},
            {"controller.area=entity:x", "area"},
            {"controller.think=-1s", "think"},
            {"controller.reconnect=0s", "reconnect"},
        };
        for (String[] b : bad) {
            runner.withPropertyValues("controller.api-key=k", b[0]).run(ctx ->
                    assertThat(ctx).as(b[0]).getFailure().rootCause().hasMessageContaining(b[1]));
        }
    }
}
