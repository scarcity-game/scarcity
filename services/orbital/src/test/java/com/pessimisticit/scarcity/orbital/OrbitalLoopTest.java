package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.orbital.StubWorld.Request;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrbitalLoopTest {

    private static final Duration INTERVAL = Duration.ofMillis(300);
    private static final Duration ANSWER_DELAY = Duration.ofMillis(200);

    @Test
    void theNextTurnStartsAnIntervalAfterTheResponse() throws Exception {
        try (StubWorld world = new StubWorld()) {
            world.delay(ANSWER_DELAY);
            OrbitalLoop loop = loop(world);
            loop.start();
            try {
                waitFor(world, 9);
            } finally {
                loop.stop();
            }
            assertThat(loop.isRunning()).isFalse();
            List<Request> requests = world.requests;
            for (int i = 1; i < 8; i += 2) {
                Request post = requests.get(i);
                Request nextGet = requests.get(i + 1);
                assertThat(post.method()).isEqualTo("POST");
                assertThat(nextGet.method()).isEqualTo("GET");
                Duration gap = Duration.between(post.answered(), nextGet.received());
                assertThat(gap).as("wait after the response of turn %d", i / 2)
                        .isGreaterThanOrEqualTo(INTERVAL)
                        .isLessThan(INTERVAL.plusMillis(250));
                // Within a turn the intent follows the read at once: the wait is only between turns.
                Duration within = Duration.between(requests.get(i - 1).answered(), post.received());
                assertThat(within).isLessThan(Duration.ofMillis(150));
            }
        }
    }

    @Test
    void stopEndsTheThreadDuringItsWait() throws Exception {
        try (StubWorld world = new StubWorld()) {
            OrbitalLoop loop = new OrbitalLoop(Fixtures.player(world.url(), Map.of("wait", 1.0), Clock.systemUTC(),
                    new SimpleMeterRegistry()), new Pacing(Duration.ofMinutes(5), Duration.ofMinutes(5)),
                    ObservationRegistry.NOOP);
            loop.start();
            waitFor(world, 2);
            long started = System.nanoTime();
            loop.stop();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals("orbital-loop"));
        }
    }

    private static OrbitalLoop loop(StubWorld world) {
        Player player = Fixtures.player(world.url(), Map.of("wait", 1.0), Clock.systemUTC(),
                new SimpleMeterRegistry());
        return new OrbitalLoop(player, new Pacing(INTERVAL, Duration.ofSeconds(30)), ObservationRegistry.NOOP);
    }

    private static void waitFor(StubWorld world, int requests) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (world.requests.size() < requests && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(world.requests).hasSizeGreaterThanOrEqualTo(requests);
    }

}
