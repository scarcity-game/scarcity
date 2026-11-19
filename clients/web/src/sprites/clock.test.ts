import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CLOCK_TICK_MS, MAX_RATE, SpriteClock } from './clock.ts';

let wall = 0;
beforeEach(() => {
  vi.useFakeTimers();
  wall = 0;
});
afterEach(() => vi.useRealTimers());

const advance = (ms: number) => {
  wall += ms;
  vi.advanceTimersByTime(ms);
};

describe('SpriteClock', () => {
  it('stands still until it runs, and ticks only while someone listens', () => {
    const clock = new SpriteClock(() => wall);
    const listener = vi.fn();
    advance(1000);
    expect(clock.now()).toBe(0);
    clock.configure({ running: true, rate: 1 });
    expect(vi.getTimerCount()).toBe(0); // running, but nobody listens
    const stop = clock.subscribe(listener);
    advance(CLOCK_TICK_MS * 3);
    expect(listener).toHaveBeenCalledTimes(3);
    expect(clock.now()).toBe(CLOCK_TICK_MS * 3);
    stop();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('runs at the rate, up to the cap, and stops when paused', () => {
    const clock = new SpriteClock(() => wall);
    clock.configure({ running: true, rate: 2 });
    clock.subscribe(() => {});
    advance(200);
    expect(clock.now()).toBe(400);
    clock.configure({ running: true, rate: 16 });
    advance(100);
    expect(clock.now()).toBe(400 + 100 * MAX_RATE);
    clock.configure({ running: false, rate: 1 });
    const frozen = clock.now();
    advance(500);
    expect(clock.now()).toBe(frozen);
    expect(vi.getTimerCount()).toBe(0);
  });
});
