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
  private running = false;
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
    void this.loop();
  }

  stop(): void {
    this.running = false;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.controller?.abort();
    this.controller = null;
  }

  clear(): void {
    this.entries = [];
    this.lastSeq = 0;
    this.error = null;
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }

  private async loop(): Promise<void> {
    const controller = new AbortController();
    this.controller = controller;
    let error: string | null = null;
    try {
      await this.syncOnce(controller.signal);
    } catch (err) {
      if (!this.running) return;
      error = err instanceof Error ? err.message : String(err);
    }
    if (!this.running) return;
    if (error !== this.error) {
      this.error = error;
      this.emit();
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.loop();
    }, this.pollMs);
  }

  private async syncOnce(signal: AbortSignal): Promise<void> {
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
