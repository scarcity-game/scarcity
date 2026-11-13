import type { LogEntry, LogPage } from '../log/types.ts';

export type FetchKindPage = (after: number, limit: number, kind: string | undefined, signal?: AbortSignal) => Promise<LogPage>;

export interface LoadOptions {
  signal?: AbortSignal;
  pageSize?: number; /*default 500*/
  onProgress?: (loaded: number, total: number) => void;
}

export type LoadResult =
  { kind: 'ok'; snapshot: LogEntry; entries: LogEntry[]; last: number } |
  { kind: 'no-snapshot' };

export class ReplayPrunedError extends Error {
  constructor() {
    super('The log was pruned while it was loading');
  }
}

async function checkAbort(options: LoadOptions): Promise<void> {
  if (options.signal?.aborted) {
    throw new DOMException('Aborted', 'AbortError');
  }
}

async function loadOnce(fetchPage: FetchKindPage, options: LoadOptions): Promise<LoadResult> {
  // a) find the LATEST snapshot
  let after = 0;
  let latest: LogEntry | null = null;

  while (true) {
    await checkAbort(options);
    const page = await fetchPage(after, 5, 'snapshot', options.signal);

    if (page.entries.length === 0) {
      break;
    }

    const newest: LogEntry = page.entries[page.entries.length - 1]!;
    latest = newest;
    after = newest.seq;

    if (page.entries.length < 5) {
      break;
    }
  }

  if (latest === null) {
    return { kind: 'no-snapshot' };
  }
  const snapshot: LogEntry = latest;

  // b) then collect the entries after it
  let cursor = snapshot.seq;
  let target: number | null = null;
  const entries: LogEntry[] = [];

  while (true) {
    await checkAbort(options);
    const page = await fetchPage(cursor, options.pageSize ?? 500, undefined, options.signal);

    if (page.first !== null && page.first > snapshot.seq) {
      throw new ReplayPrunedError();
    }

    if (target === null) {
      target = page.last ?? snapshot.seq;
    }

    for (const entry of page.entries) {
      if (entry.seq <= target) {
        entries.push(entry);
      }
    }

    options.onProgress?.(entries.length, Math.max(target - snapshot.seq, entries.length));
    const newest = page.entries[page.entries.length - 1];
    if (newest === undefined || newest.seq >= target) {
      break;
    }
    cursor = newest.seq;
  }

  // c) return the result
  return {
    kind: 'ok',
    snapshot,
    entries,
    last: target
  };
}

export async function loadReplay(fetchPage: FetchKindPage, options: LoadOptions = {}): Promise<LoadResult> {
  for (const attempt of [1, 2]) {
    try {
      return await loadOnce(fetchPage, options);
    } catch (error) {
      if (error instanceof ReplayPrunedError && attempt === 2) {
        throw error;
      }
      if (!(error instanceof ReplayPrunedError)) {
        throw error;
      }
      // If it's a ReplayPrunedError and we're on the first attempt, continue to second attempt
    }
  }

  // This should never be reached due to the loop structure above
  throw new Error('Unexpected state in loadReplay');
}