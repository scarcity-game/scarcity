package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code controller.mode} picks one way to be told the turns, and checks what that way needs. */
class ControllerModeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ControllerAutoConfiguration.class))
            .withBean(Choice.class, () -> Fixtures.named("wait"))
            .withBean(SimpleMeterRegistry.class)
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withPropertyValues("controller.principal=tester", "controller.weights.wait=1");

    @Test
    void endpointModeNeedsNoKeyNoWorldAndNoSocketSettingsButItsJwks() {
        runner.withPropertyValues("controller.mode=endpoint", "controller.jwk-set-uri=http://localhost:1/jwks.json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TurnEndpoint.class);
                    assertThat(context).doesNotHaveBean(Player.class);
                });
        runner.withPropertyValues("controller.mode=endpoint")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void socketModeIsTheDefaultAndNeedsItsKeyAndUrls() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("controller.mode=socket").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnknownModeFailsStartup() {
        runner.withPropertyValues("controller.mode=endpont").run(context -> assertThat(context).hasFailed());
    }
}
