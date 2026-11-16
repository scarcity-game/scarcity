package com.pessimisticit.scarcity.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** Defaults, validation and use of the world call timeouts. */
class SubscriptionsPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void worldTimeoutsDefaultTo3sAnd10s() {
        SubscriptionsProperties subscriptionsProperties = new SubscriptionsProperties();

        assertThat(subscriptionsProperties.getWorldConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(subscriptionsProperties.getWorldReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(validator.validate(subscriptionsProperties)).isEmpty();
    }

    @Test
    void missingWorldTimeoutsAreRejected() {
        SubscriptionsProperties subscriptionsProperties = new SubscriptionsProperties();
        subscriptionsProperties.setWorldConnectTimeout(null);
        subscriptionsProperties.setWorldReadTimeout(null);

        assertThat(validator.validate(subscriptionsProperties)).hasSize(2);
    }

    @Test
    void worldClientAppliesConfiguredTimeouts() {
        SubscriptionsProperties subscriptionsProperties = new SubscriptionsProperties();
        subscriptionsProperties.setWorldConnectTimeout(Duration.ofMillis(1234));
        subscriptionsProperties.setWorldReadTimeout(Duration.ofMillis(5678));
        RestClient.Builder builder = RestClient.builder();

        new WorldClient(builder, subscriptionsProperties);

        Object factory = ReflectionTestUtils.getField(builder, "requestFactory");
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(1234);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(5678);
    }
}
