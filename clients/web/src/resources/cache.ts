import { useSyncExternalStore } from 'react';

/** A resource exactly as the world API returns it. */
export interface Resource {
  id: string;
  type: string;
  version: number;
  children: string[];
  state: Record<string, unknown>;
}

/** In-memory resources keyed by id. Snapshots are immutable and replaced on every change. */
export class ResourceStore {
  private items: ReadonlyMap<string, Resource> = new Map();
  private staleIds: ReadonlySet<string> = new Set();
  private listeners = new Set<() => void>();

  get = (id: string): Resource | undefined => this.items.get(id);

  /** For useSyncExternalStore: a new Map instance after each change. */
  getSnapshot = (): ReadonlyMap<string, Resource> => this.items;

  isStale(id: string): boolean {
    return this.staleIds.has(id);
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** Stores a fetched resource unless we already hold a newer version. */
  put(resource: Resource): boolean {
    const cached = this.items.get(resource.id);
    if (cached && cached.version > resource.version) return false;
    this.set(resource);
    return true;
  }

  /** Applies a patch: it replaces the state entirely and sets the version. */
  applyPatch(id: string, version: number, patch: Record<string, unknown>): boolean {
    const cached = this.items.get(id);
    if (!cached) return false;
    this.set({ ...cached, version, state: patch });
    return true;
  }

  /** Marks a resource as out of date (it missed an update) until the next put. */
  markStale(id: string): void {
    if (this.staleIds.has(id) || !this.items.has(id)) return;
    this.staleIds = new Set(this.staleIds).add(id);
    this.items = new Map(this.items); // new snapshot so subscribers re-render
    this.emit();
  }

  clearStale(id: string): void {
    if (!this.staleIds.has(id)) return;
    const next = new Set(this.staleIds);
    next.delete(id);
    this.staleIds = next;
    this.items = new Map(this.items);
    this.emit();
  }

  clear(): void {
    this.items = new Map();
    this.staleIds = new Set();
    this.emit();
  }

  private set(resource: Resource): void {
    const next = new Map(this.items);
    next.set(resource.id, resource);
    this.items = next;
    if (this.staleIds.has(resource.id)) {
      const stale = new Set(this.staleIds);
      stale.delete(resource.id);
      this.staleIds = stale;
    }
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }
}

/** All cached resources, re-rendering on change. */
export function useResources(store: ResourceStore): ReadonlyMap<string, Resource> {
  return useSyncExternalStore(store.subscribe, store.getSnapshot);
}
