import { useSyncExternalStore } from 'react';
import type { LogEntry, LogPage } from './types.ts';

export type FetchPage = (after: number, limit: number, signal?: AbortSignal) => Promise<LogPage>;

export const MAX_ENTRIES = 2000;

export interface LogStoreOptions {
  pollMs?: number;
  pageSize?: number;
  max?: number;
}

/**
 * The newest entries of one area's log (at most `max`), kept in a store a
 * replay can reuse. It pages from `after` 0 until it reaches `last`, then
 * polls for what is new.
 */
export class LogStore {
  private entries: readonly LogEntry[] = [];
  private error: string | null = null;
  private lastSeq = 0;
  private positioned = false;
  private running = false;
  private live = false;
  private seenLogSeq = false;
  private reading = false;
  private again = false;
  private requested = 0;
  private gen = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private controller: AbortController | null = null;
  private listeners = new Set<() => void>();
  private readonly pollMs: number;
  private readonly pageSize: number;
  private readonly max: number;

  constructor(
    private readonly fetchPage: FetchPage,
    options: LogStoreOptions = {},
  ) {
    this.pollMs = options.pollMs ?? 1000;
    this.pageSize = options.pageSize ?? 500;
    this.max = options.max ?? MAX_ENTRIES;
  }

  getSnapshot = (): readonly LogEntry[] => this.entries;

  getError = (): string | null => this.error;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.readNow();
  }

  stop(): void {
    this.running = false;
    this.gen += 1;
    this.reading = false;
    this.again = false;
    this.requested = 0;
    this.clearTimer();
    this.controller?.abort();
    this.controller = null;
  }

  clear(): void {
    this.entries = [];
    this.lastSeq = 0;
    this.positioned = false;
    this.requested = 0;
    this.error = null;
    this.emit();
  }

  /**
   * Tell the store whether the subscriptions socket is live. While it is not,
   * the store polls every pollMs. On becoming live it reads once, and keeps
   * polling until the first logSeq arrives (an older server never sends one).
   */
  setLive(live: boolean): void {
    if (live === this.live) return;
    this.live = live;
    this.seenLogSeq = false;
    if (!this.running) return;
    if (live) {
      void this.readNow();
    } else {
      this.schedule();
    }
  }

  /** The log now ends at seq `n` (logSeq of a changes or digest message). */
  notifyLogSeq(n: number): void {
    if (!this.running) return;
    this.seenLogSeq = true;
    if (this.live && !this.reading) this.clearTimer();
    if (n <= this.lastSeq) return;
    if (this.reading) {
      // A read is in flight; one more follows only if n is beyond what it was asked for.
      if (n > this.requested) {
        this.requested = n;
        this.again = true;
      }
    } else {
      this.requested = n;
      void this.readNow();
    }
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  private schedule(): void {
    if (!this.running || this.reading || this.timer !== null) return;
    if (this.live && this.seenLogSeq) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.readNow();
    }, this.pollMs);
  }

  private async readNow(): Promise<void> {
    if (!this.running || this.reading) return;
    this.reading = true;
    this.clearTimer();
    const gen = this.gen;
    const controller = new AbortController();
    this.controller = controller;
    do {
      this.again = false;
      let error: string | null = null;
      try {
        await this.syncOnce(controller.signal);
      } catch (err) {
        if (gen !== this.gen) return;
        error = err instanceof Error ? err.message : String(err);
      }
      if (gen !== this.gen) return;
      if (error !== this.error) {
        this.error = error;
        this.emit();
      }
      if (error !== null) this.again = false;
    } while (this.again);
    this.reading = false;
    this.schedule();
  }

  private async syncOnce(signal: AbortSignal): Promise<void> {
    if (!this.positioned) {
      // First sync: ask for one entry to learn first and last, then start so
      // that at most `max` entries are loaded.
      const probe = await this.fetchPage(0, 1, signal);
      if (!this.running) return;
      if (probe.first === null || probe.last === null) return;
      this.lastSeq = Math.max(probe.first - 1, probe.last - this.max);
      this.positioned = true;
    }
    for (;;) {
      const page = await this.fetchPage(this.lastSeq, this.pageSize, signal);
      if (!this.running) return;
      if (page.first !== null && page.first > this.lastSeq + 1) {
        // Pruned: what we hold is no longer contiguous with the log. Start over.
        this.entries = [];
        this.lastSeq = page.first - 1;
        this.emit();
        continue;
      }
      const newest = page.entries[page.entries.length - 1];
      if (newest === undefined) return;
      const all = [...this.entries, ...page.entries];
      this.entries = all.length > this.max ? all.slice(all.length - this.max) : all;
      this.lastSeq = newest.seq;
      this.emit();
      if (page.last === null || this.lastSeq >= page.last) return;
    }
  }
}

export function useLogEntries(store: LogStore): readonly LogEntry[] {
  return useSyncExternalStore(store.subscribe, store.getSnapshot);
}

export function useLogError(store: LogStore): string | null {
  return useSyncExternalStore(store.subscribe, store.getError);
}
