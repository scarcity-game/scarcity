import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LogStore } from './logStore.ts';
import type { LogEntry, LogPage } from './types.ts';

const entry = (seq: number): LogEntry => ({
  seq,
  area: 'area:a',
  time: seq,
  kind: 'effect',
  cause: null,
  root: seq,
  resource: null,
  draws: [],
  changes: [],
});

const range = (from: number, to: number): LogEntry[] =>
  Array.from({ length: to - from + 1 }, (_, i) => entry(from + i));

/** A fake server over `log.entries`. */
function fakeServer(initial: LogEntry[]) {
  const log = { entries: initial };
  const calls: Array<{ after: number; limit: number }> = [];
  const fetchPage = vi.fn(async (after: number, limit: number): Promise<LogPage> => {
    calls.push({ after, limit });
    const held = log.entries;
    return {
      entries: held.filter((e) => e.seq > after).slice(0, limit),
      first: held[0]?.seq ?? null,
      last: held[held.length - 1]?.seq ?? null,
    };
  });
  return { log, calls, fetchPage };
}

/** A gated server that can block reads. */
function gated(initial: LogEntry[]) {
  const server = fakeServer(initial);
  let gate: Promise<void> | null = null;
  let release: () => void = () => {};
  const fetchPage = vi.fn(async (after: number, limit: number) => {
    const g = gate;
    if (g) await g;
    return server.fetchPage(after, limit);
  });
  return {
    server,
    fetchPage,
    close() {
      gate = new Promise<void>((r) => {
        release = r;
      });
    },
    open() {
      gate = null;
      release();
    }
  };
}

const seqs = (store: LogStore) => store.getSnapshot().map((e) => e.seq);

describe('LogStore live mode', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('a higher logSeq reads once', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    server.log.entries = range(1, 3);
    store.notifyLogSeq(3);
    await vi.advanceTimersByTimeAsync(0);

    expect(seqs(store)).toEqual([1, 2, 3]);
    expect(server.calls.length).toBe(3); // Initial + first read + notify read
    expect(server.calls[server.calls.length - 1]).toEqual({ after: 2, limit: 500 });
  });

  it('an equal or lower logSeq does nothing', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    const initialCallsLength = server.calls.length;
    store.notifyLogSeq(2);
    store.notifyLogSeq(1);
    await vi.advanceTimersByTimeAsync(0);

    expect(server.calls.length).toBe(initialCallsLength);
  });

  it('runs no timer once a logSeq has been seen', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    store.notifyLogSeq(2); // Equal - no read
    const initialCallsLength = server.calls.length;
    await vi.advanceTimersByTimeAsync(60000);
    expect(server.calls.length).toBe(initialCallsLength);
  });

  it('keeps polling every 5 s while live until the first logSeq', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    const initialCallsLength = server.calls.length;

    // Advance 4999ms - should not trigger another call
    await vi.advanceTimersByTimeAsync(4999);
    expect(server.calls.length).toBe(initialCallsLength);

    // Advance 1 more ms - should trigger the first read
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls.length).toBe(initialCallsLength + 1);

    // Advance another 5000ms to trigger second read
    await vi.advanceTimersByTimeAsync(5000);
    expect(server.calls.length).toBe(initialCallsLength + 2);

    // Now notify a logSeq, which should prevent further polling
    store.notifyLogSeq(2);
    await vi.advanceTimersByTimeAsync(60000);
    expect(server.calls.length).toBe(initialCallsLength + 2);
  });

  it('polls every 5 s when not live', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    // Not setting live mode - should default to not live
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    const initialCallsLength = server.calls.length;
    await vi.advanceTimersByTimeAsync(4999);
    expect(server.calls.length).toBe(initialCallsLength);

    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls.length).toBe(initialCallsLength + 1);
  });

  it('going live reads once and going not live resumes polling', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    // Start not live
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    const beforeLive = server.calls.length;

    // Going live reads once.
    store.setLive(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(server.calls.length).toBe(beforeLive + 1);
    const initialCallsLength = server.calls.length;

    // Now notify again with same seq - should do nothing
    store.notifyLogSeq(2);
    await vi.advanceTimersByTimeAsync(0);

    // Check no calls were made
    expect(server.calls.length).toBe(initialCallsLength);

    // Now set not live again - should resume polling
    store.setLive(false);
    await vi.advanceTimersByTimeAsync(5000);

    // Should have one more call now
    expect(server.calls.length).toBe(initialCallsLength + 1);
  });

  it('stop() ends the polling', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage, { pollMs: 5000 });
    // Start not live
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    store.stop();
    const initialCallsLength = server.calls.length;
    await vi.advanceTimersByTimeAsync(60000);

    expect(server.calls.length).toBe(initialCallsLength);
  });

  it('a read in flight is not doubled', async () => {
    const g = gated(range(1, 2));
    const store = new LogStore(g.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seqs(store)).toEqual([1, 2]);

    g.close();
    g.server.log.entries = range(1, 3);
    store.notifyLogSeq(3); // This starts a read that blocks on the gate
    store.notifyLogSeq(3); // This should not cause another read
    store.notifyLogSeq(3); // This should not cause another read

    const n = g.fetchPage.mock.calls.length;

    g.open();
    await vi.advanceTimersByTimeAsync(0);

    expect(seqs(store)).toEqual([1, 2, 3]);
    expect(g.fetchPage.mock.calls.length).toBe(n); // No extra reads
  });

  it('a logSeq that moves on during a read causes one more read', async () => {
    const g = gated(range(1, 2));
    const store = new LogStore(g.fetchPage, { pollMs: 5000 });
    store.setLive(true);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seqs(store)).toEqual([1, 2]);

    g.close();
    g.server.log.entries = range(1, 3);
    store.notifyLogSeq(3);
    await vi.advanceTimersByTimeAsync(0);

    g.server.log.entries = range(1, 4);
    store.notifyLogSeq(4);

    const n = g.fetchPage.mock.calls.length;

    g.open();
    await vi.advanceTimersByTimeAsync(0);

    expect(seqs(store)).toEqual([1, 2, 3, 4]);
    // Expect exactly one more fetch after the initial ones
    expect(g.fetchPage.mock.calls.length).toBe(n + 1);
  });
});