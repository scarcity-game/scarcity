package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class FreshSeedTest extends DbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void seedsTheControlledOrbiterAtTimeZero() {
        assertThat(seeder().seed()).isTrue();

        assertThat(version(WorldIds.GAME)).isEqualTo(2);
        assertThat(version(WorldIds.AREA)).isEqualTo(3);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(1);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
        assertThat(get(WorldIds.GAME).children()).containsExactly(WorldIds.AREA);
        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);

        JsonNode area = get(WorldIds.AREA).state();
        assertThat(area.get("time").asDouble()).isZero();
        assertThat(area.has("clock")).isFalse();
        // The orbiter is up from time 0 (#148).
        assertThat(area.get("turns")).hasSize(1);
        assertThat(area.get("turns").get(0).get("entity").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(area.get("turns").get(0).get("controller").get("principal").asText()).isEqualTo("orbital");
        assertThat(area.get("turns").get(0).get("since").asDouble()).isZero();
        JsonNode orbiter = get(WorldIds.ORBITER).state();
        assertThat(orbiter.has("behaviour")).isFalse();
        assertThat(orbiter.get("controller")).isEqualTo(MAPPER.createObjectNode()
                .put("kind", "service").put("principal", "orbital"));
        assertThat(orbiter.get("actions")).extracting(JsonNode::asText).containsExactly("step", "wait", "throw");
        assertThat(orbiter.get("position").get("x").asDouble()).isEqualTo(8.0);
        assertThat(orbiter.get("speed").asDouble()).isEqualTo(0.5);
    }

    @Test
    void seedingAgainWritesNothing() {
        seeder().seed();

        assertThat(seeder().seed()).isFalse();

        assertThat(version(WorldIds.AREA)).isEqualTo(3);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
        assertThat(JDBC.queryForObject("SELECT count(*) FROM area_log", Long.class)).isZero();
    }

    @Test
    void twoInstancesSeedingAtOnceSeedOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> runs = List.of(1, 2).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(go);
                    return seeder().seed();
                }))
                .toList();
        go.countDown();

        assertThat(runs.stream().map(CompletableFuture::join))
                .containsExactlyInAnyOrder(true, false);
        assertThat(version(WorldIds.AREA)).isEqualTo(3);
        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
