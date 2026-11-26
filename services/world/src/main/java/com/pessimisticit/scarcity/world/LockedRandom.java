package com.pessimisticit.scarcity.world;

import java.util.random.RandomGenerator;

/**
 * A {@link RandomGenerator} that one request at a time may draw from. The service's source is a
 * {@code SplittableRandom}, whose state is two plain longs: unsynchronised, concurrent requests on
 * different areas (advances, crossings, ids) could lose an update and draw the same value twice. Each
 * call delegates under one lock, so for a single thread the stream is exactly the delegate's, and the
 * logged seed still replays it. Every method an engine or draw uses is delegated, not rebuilt from
 * {@link #nextLong()}, so that holds for them too.
 */
final class LockedRandom implements RandomGenerator {

    private final RandomGenerator delegate;

    LockedRandom(RandomGenerator delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized long nextLong() {
        return delegate.nextLong();
    }

    @Override
    public synchronized int nextInt() {
        return delegate.nextInt();
    }

    @Override
    public synchronized int nextInt(int bound) {
        return delegate.nextInt(bound);
    }

    @Override
    public synchronized int nextInt(int origin, int bound) {
        return delegate.nextInt(origin, bound);
    }

    @Override
    public synchronized long nextLong(long bound) {
        return delegate.nextLong(bound);
    }

    @Override
    public synchronized long nextLong(long origin, long bound) {
        return delegate.nextLong(origin, bound);
    }

    @Override
    public synchronized double nextDouble() {
        return delegate.nextDouble();
    }

    @Override
    public synchronized double nextDouble(double bound) {
        return delegate.nextDouble(bound);
    }

    @Override
    public synchronized double nextDouble(double origin, double bound) {
        return delegate.nextDouble(origin, bound);
    }

    @Override
    public synchronized float nextFloat() {
        return delegate.nextFloat();
    }

    @Override
    public synchronized boolean nextBoolean() {
        return delegate.nextBoolean();
    }

    @Override
    public synchronized double nextGaussian() {
        return delegate.nextGaussian();
    }

    @Override
    public synchronized double nextGaussian(double mean, double stddev) {
        return delegate.nextGaussian(mean, stddev);
    }

    @Override
    public synchronized double nextExponential() {
        return delegate.nextExponential();
    }

    @Override
    public synchronized void nextBytes(byte[] bytes) {
        delegate.nextBytes(bytes);
    }
}
