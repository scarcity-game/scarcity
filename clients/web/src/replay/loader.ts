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

const SNAPSHOT_PAGE = 5;

// The LATEST snapshot in the log, or null if there is none.
async function findLatestSnapshot(
  fetchPage: FetchKindPage,
  options: LoadOptions,
): Promise<LogEntry | null> {
  let after = 0;
  let latest: LogEntry | null = null;
  for (;;) {
    await checkAbort(options);
    const page = await fetchPage(after, SNAPSHOT_PAGE, 'snapshot', options.signal);
    const newest = page.entries[page.entries.length - 1];
    if (newest === undefined) return latest;
    latest = newest;
    after = newest.seq;
    if (page.entries.length < SNAPSHOT_PAGE) return latest;
  }
}

function checkNotPruned(page: LogPage, snapshot: LogEntry): void {
  if (page.first !== null && page.first > snapshot.seq) throw new ReplayPrunedError();
}

// The entries after `snapshot`, up to the log's last seq as of the first page.
async function collectAfter(
  fetchPage: FetchKindPage,
  options: LoadOptions,
  snapshot: LogEntry,
): Promise<{ entries: LogEntry[]; last: number }> {
  let cursor = snapshot.seq;
  let target: number | null = null;
  const entries: LogEntry[] = [];
  for (;;) {
    await checkAbort(options);
    const page = await fetchPage(cursor, options.pageSize ?? 500, undefined, options.signal);
    checkNotPruned(page, snapshot);
    const last: number = target ?? page.last ?? snapshot.seq;
    target = last;
    entries.push(...page.entries.filter((entry) => entry.seq <= last));
    options.onProgress?.(entries.length, Math.max(last - snapshot.seq, entries.length));
    const newest = page.entries[page.entries.length - 1];
    if (newest === undefined || newest.seq >= last) return { entries, last };
    cursor = newest.seq;
  }
}

async function loadOnce(fetchPage: FetchKindPage, options: LoadOptions): Promise<LoadResult> {
  // a) find the LATEST snapshot
  const snapshot = await findLatestSnapshot(fetchPage, options);
  if (snapshot === null) return { kind: 'no-snapshot' };
  // b) then collect the entries after it, c) and return the result
  const { entries, last } = await collectAfter(fetchPage, options, snapshot);
  return { kind: 'ok', snapshot, entries, last };
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