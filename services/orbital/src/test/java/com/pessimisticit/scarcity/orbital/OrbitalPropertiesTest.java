package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml's {@code orbital} block, bound and checked by Bean Validation. */
class OrbitalPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrbitalProperties.class)
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
        runner.withPropertyValues("orbital.api-key=" + Fixtures.KEY).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            OrbitalProperties props = ctx.getBean(OrbitalProperties.class);
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
            {"orbital.weights.step=-1", "weights[step]"},
            {"orbital.step.radius=0", "stepSettings.radius"},
            {"orbital.wait.duration=-1", "waitSettings.duration"},
            {"orbital.throw.spread=-0.1", "throwSettings.spread"},
            {"orbital.throw.recovery=0", "throwSettings.recovery"},
            {"orbital.throw.target=pillar", "throwSettings.target"},
            {"orbital.area=entity:x", "area"},
            {"orbital.think=-1s", "think"},
            {"orbital.reconnect=0s", "reconnect"},
        };
        for (String[] b : bad) {
            runner.withPropertyValues("orbital.api-key=k", b[0]).run(ctx ->
                    assertThat(ctx).as(b[0]).getFailure().rootCause().hasMessageContaining(b[1]));
        }
    }
}
