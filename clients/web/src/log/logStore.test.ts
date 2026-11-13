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

const seqs = (store: LogStore) => store.getSnapshot().map((e) => e.seq);

describe('LogStore', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('pages with after/limit until it reaches last', async () => {
    const server = fakeServer(range(1, 7));
    const store = new LogStore(server.fetchPage, { pageSize: 3 });
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(server.calls).toEqual([
      { after: 0, limit: 3 },
      { after: 3, limit: 3 },
      { after: 6, limit: 3 },
    ]);
    expect(seqs(store)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    store.stop();
  });

  it('polls about once a second for new entries', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seqs(store)).toEqual([1, 2]);
    server.log.entries = range(1, 4);
    await vi.advanceTimersByTimeAsync(999);
    expect(seqs(store)).toEqual([1, 2]);
    await vi.advanceTimersByTimeAsync(1);
    expect(seqs(store)).toEqual([1, 2, 3, 4]);
    expect(server.calls[server.calls.length - 1]).toEqual({ after: 2, limit: 500 });
    store.stop();
  });

  it('stops polling after stop()', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    store.stop();
    const count = server.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(server.calls.length).toBe(count);
  });

  it('restarts from first - 1 when the server pruned what it holds next', async () => {
    const server = fakeServer(range(1, 3));
    const store = new LogStore(server.fetchPage);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seqs(store)).toEqual([1, 2, 3]);
    server.log.entries = range(10, 12);
    await vi.advanceTimersByTimeAsync(1000);
    expect(seqs(store)).toEqual([10, 11, 12]);
    expect(server.calls).toContainEqual({ after: 9, limit: 500 });
    store.stop();
  });

  it('keeps at most the newest max entries', async () => {
    const server = fakeServer(range(1, 12));
    const store = new LogStore(server.fetchPage, { max: 5, pageSize: 4 });
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seqs(store)).toEqual([8, 9, 10, 11, 12]);
    store.stop();
  });

  it('caps at 2,000 entries by default', async () => {
    const server = fakeServer(range(1, 2500));
    const store = new LogStore(server.fetchPage);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    const held = seqs(store);
    expect(held.length).toBe(2000);
    expect(held[0]).toBe(501);
    expect(held[1999]).toBe(2500);
    store.stop();
  });

  it('records a fetch error and keeps polling', async () => {
    const server = fakeServer(range(1, 2));
    let fail = true;
    const store = new LogStore(async (after, limit) => {
      if (fail) throw new Error('boom');
      return server.fetchPage(after, limit);
    });
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.getError()).toBe('boom');
    fail = false;
    await vi.advanceTimersByTimeAsync(1000);
    expect(store.getError()).toBeNull();
    expect(seqs(store)).toEqual([1, 2]);
    store.stop();
  });

  it('returns the same snapshot when a poll adds nothing', async () => {
    const server = fakeServer(range(1, 2));
    const store = new LogStore(server.fetchPage);
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    const before = store.getSnapshot();
    await vi.advanceTimersByTimeAsync(1000);
    expect(store.getSnapshot()).toBe(before);
    store.stop();
  });
});
