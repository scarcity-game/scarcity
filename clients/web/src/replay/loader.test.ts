import { describe, expect, it, vi } from 'vitest';
import type { LogEntry } from '../log/types.ts';
import { loadReplay, ReplayPrunedError, type FetchKindPage } from './loader.ts';

const e = (seq: number, kind = 'effect'): LogEntry => ({
  seq,
  area: 'area:a',
  time: seq,
  kind,
  cause: null,
  root: seq,
  resource: null,
  draws: [],
  changes: [],
});

const range = (from: number, to: number): LogEntry[] =>
  Array.from({ length: to - from + 1 }, (_, i) => e(from + i));

/** A log served like the world API; `log.entries` can be replaced between calls. */
function fakeLog(entries: LogEntry[], onCall?: (log: Fake, after: number, kind: string | undefined) => void) {
  const log: Fake = { entries, calls: [], fetchPage: undefined as unknown as FetchKindPage };
  log.fetchPage = async (after, limit, kind, signal) => {
    log.calls.push([after, limit, kind]);
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const all = log.entries;
    const matching = all.filter((x) => x.seq > after && (kind === undefined || x.kind === kind));
    const page = {
      entries: matching.slice(0, limit),
      first: all[0]?.seq ?? null,
      last: all[all.length - 1]?.seq ?? null,
    };
    onCall?.(log, after, kind);
    return page;
  };
  return log;
}
interface Fake {
  entries: LogEntry[];
  calls: [number, number, string | undefined][];
  fetchPage: FetchKindPage;
}

const bigLog = (): LogEntry[] => [e(1, 'snapshot'), ...range(2, 4), e(5, 'snapshot'), ...range(6, 1200)];

describe('loadReplay', () => {
  it('finds the latest snapshot and loads every entry after it', async () => {
    const log = fakeLog(bigLog());
    const result = await loadReplay(log.fetchPage, { pageSize: 500 });
    if (result.kind !== 'ok') throw new Error('expected ok');
    expect(result.snapshot.seq).toBe(5);
    expect(result.entries).toHaveLength(1195);
    expect(result.entries[0]?.seq).toBe(6);
    expect(result.last).toBe(1200);
    expect(log.calls.some(([, limit, kind]) => limit === 500 && kind === undefined)).toBe(true);
    expect(log.calls.some(([, , kind]) => kind === 'snapshot')).toBe(true);
  });

  it('reports progress', async () => {
    const log = fakeLog(bigLog());
    const onProgress = vi.fn();
    await loadReplay(log.fetchPage, { pageSize: 500, onProgress });
    expect(onProgress.mock.calls.length).toBeGreaterThan(1);
    expect(onProgress.mock.calls[onProgress.mock.calls.length - 1]).toEqual([1195, 1195]);
  });

  it('gives no-snapshot when the log has none, or is empty', async () => {
    expect(await loadReplay(fakeLog(range(1, 10)).fetchPage)).toEqual({ kind: 'no-snapshot' });
    expect(await loadReplay(fakeLog([]).fetchPage)).toEqual({ kind: 'no-snapshot' });
  });

  it('fixes the range once loaded', async () => {
    let grown = false;
    const log = fakeLog([e(1, 'snapshot'), ...range(2, 600)], (l, after, kind) => {
      if (kind === undefined && after === 1 && !grown) {
        grown = true;
        l.entries = [...l.entries, ...range(601, 700)];
      }
    });
    const result = await loadReplay(log.fetchPage, { pageSize: 500 });
    if (result.kind !== 'ok') throw new Error('expected ok');
    expect(result.last).toBe(600);
    expect(result.entries[result.entries.length - 1]?.seq).toBe(600);
  });

  it('can be cancelled between pages', async () => {
    const controller = new AbortController();
    const log = fakeLog(bigLog(), (_l, _a, kind) => {
      if (kind === undefined) controller.abort();
    });
    await expect(loadReplay(log.fetchPage, { pageSize: 500, signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    });
  });

  it('rejects at once with an already aborted signal', async () => {
    const controller = new AbortController();
    controller.abort();
    const log = fakeLog(bigLog());
    await expect(loadReplay(log.fetchPage, { signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    });
    expect(log.calls).toHaveLength(0);
  });

  it('restarts once when the log is pruned underneath', async () => {
    let pruned = false;
    const log = fakeLog([e(1, 'snapshot'), ...range(2, 900)], (l, _a, kind) => {
      if (kind === undefined && !pruned) {
        pruned = true;
        l.entries = [e(800, 'snapshot'), ...range(801, 900)];
      }
    });
    const result = await loadReplay(log.fetchPage, { pageSize: 500 });
    if (result.kind !== 'ok') throw new Error('expected ok');
    expect(result.snapshot.seq).toBe(800);
    expect(result.entries).toHaveLength(100);
  });

  it('gives an error when the log is pruned again on the restart', async () => {
    const log = fakeLog([e(1, 'snapshot'), ...range(2, 900)], (l, _a, kind) => {
      if (kind === undefined) l.entries = [e(l.entries[0]!.seq + 10, 'snapshot'), ...range(l.entries[0]!.seq + 11, 900)];
    });
    await expect(loadReplay(log.fetchPage, { pageSize: 500 })).rejects.toBeInstanceOf(ReplayPrunedError);
  });

  it('rethrows a fetch error', async () => {
    const fetchPage: FetchKindPage = async () => {
      throw new Error('boom');
    };
    await expect(loadReplay(fetchPage)).rejects.toThrow('boom');
  });

  it('loads a snapshot with nothing after it', async () => {
    const result = await loadReplay(fakeLog([e(3, 'snapshot')]).fetchPage);
    expect(result).toMatchObject({ kind: 'ok', entries: [], last: 3 });
  });
});
