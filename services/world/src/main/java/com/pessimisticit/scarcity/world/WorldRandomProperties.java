package com.pessimisticit.scarcity.world;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code world.random}: the seed of the service's one random source (env {@code WORLD_RANDOM_SEED}).
 * Absent: a fresh one each start, logged so a run can be replayed. A test sets it so the levels it
 * generates are the same every run.
 */
@Validated
@ConfigurationProperties("world.random")
record WorldRandomProperties(Long seed) {
}
