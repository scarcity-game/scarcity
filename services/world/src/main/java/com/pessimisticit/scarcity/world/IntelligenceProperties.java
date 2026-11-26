package com.pessimisticit.scarcity.world;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code world.intelligence}: the intelligence services world POSTs turns to (docs/actions.md,
 * "Intelligence services"; #373), and how it dispatches them.
 *
 * @param services the registry: principal to its base URL and range. Empty: no turn is dispatched
 * @param authUrl auth's base URL, where world exchanges {@code apiKey} for its service token
 * @param apiKey world's service principal key (env {@code WORLD_API_KEY}); required when
 *     {@code services} isn't empty. Never logged
 * @param workers how many turns are dispatched at once
 * @param timeout the longest wait for one service's answer
 * @param lease how long a claimed turn is hidden from other dispatchers: more than {@code timeout}
 * @param poll how often due turns (retries, other pods' commits) are looked for
 * @param backoff the first retry's delay; each later one doubles, with jitter
 * @param backoffMax the longest retry delay
 */
@Validated
@ConfigurationProperties("world.intelligence")
record IntelligenceProperties(
        @NotNull Map<String, @Valid @NotNull Service> services,
        URI authUrl,
        String apiKey,
        @Min(1) int workers,
        @NotNull @DurationMin(millis = 1) Duration timeout,
        @NotNull @DurationMin(millis = 1) Duration lease,
        @NotNull @DurationMin(millis = 1) Duration poll,
        @NotNull @DurationMin(millis = 1) Duration backoff,
        @NotNull @DurationMin(millis = 1) Duration backoffMax) {

    /** One intelligence service: where it listens, and how far the context of its turns reaches. */
    record Service(@NotNull URI baseUrl, @DecimalMin("0.0") double range) {
    }

    IntelligenceProperties {
        services = services == null ? Map.of() : Map.copyOf(services);
    }

    boolean registered(String principal) {
        return services.containsKey(principal);
    }

    @AssertTrue(message = "auth-url and api-key are required when a service is registered")
    boolean isCredentialed() {
        return services.isEmpty() || authUrl != null && apiKey != null && !apiKey.isBlank();
    }

    @AssertTrue(message = "lease must be longer than timeout")
    boolean isLeaseLongerThanTimeout() {
        return lease == null || timeout == null || lease.compareTo(timeout) > 0;
    }

    @AssertTrue(message = "backoff-max must be at least backoff")
    boolean isBackoffOrdered() {
        return backoff == null || backoffMax == null || backoffMax.compareTo(backoff) >= 0;
    }
}
