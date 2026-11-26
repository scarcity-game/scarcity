package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LockedRandomTest {

    @Test
    void aSingleThreadGetsExactlyTheDelegatesStream() {
        LockedRandom locked = new LockedRandom(new SplittableRandom(7));
        SplittableRandom plain = new SplittableRandom(7);

        assertThat(locked.nextLong()).isEqualTo(plain.nextLong());
        assertThat(locked.nextInt(10)).isEqualTo(plain.nextInt(10));
        assertThat(locked.nextDouble()).isEqualTo(plain.nextDouble());
        assertThat(locked.nextDouble(1.0, 3.0)).isEqualTo(plain.nextDouble(1.0, 3.0));
        assertThat(locked.nextGaussian()).isEqualTo(plain.nextGaussian());
        assertThat(locked.nextLong(5L, 99L)).isEqualTo(plain.nextLong(5L, 99L));
    }

    @Test
    void concurrentDrawsNeverRepeatOrSkipAValue() throws Exception {
        int threads = 8;
        int each = 20_000;
        LockedRandom locked = new LockedRandom(new SplittableRandom(42));
        CountDownLatch go = new CountDownLatch(1);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> runs = IntStream.range(0, threads)
                    .mapToObj(t -> pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < each; i++) {
                            seen.add(locked.nextLong());
                        }
                        return null;
                    })).collect(java.util.stream.Collectors.toList());
            go.countDown();
            for (var run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // With the delegate's state shared unlocked, updates are lost and values repeat.
        Set<Long> expected = new HashSet<>();
        SplittableRandom plain = new SplittableRandom(42);
        for (int i = 0; i < threads * each; i++) {
            expected.add(plain.nextLong());
        }
        assertThat(seen).isEqualTo(expected);
    }
}
