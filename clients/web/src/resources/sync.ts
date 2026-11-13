import type { Resource, ResourceStore } from './cache.ts';
import { decideChange, decideDigestEntry } from './versionRule.ts';

export interface Change {
  id: string;
  type?: string;
  version?: number;
  patch?: Record<string, unknown> | null;
  children?: string[];
  deleted?: boolean;
}

/** GETs a resource; `cachedVersion` is sent as If-None-Match, a 304 gives undefined. */
export type FetchResource = (id: string, cachedVersion?: number) => Promise<Resource | undefined>;

/**
 * Keeps a ResourceStore in step with the server for one area, from the
 * subscriptions socket's `digest` and `changes` frames.
 */
export class AreaSync {
  private inFlight = new Map<string, { again: boolean }>();
  private closed = false;

  constructor(
    private readonly areaId: string,
    private readonly store: ResourceStore,
    private readonly fetchResource: FetchResource,
    private readonly onError: (message: string) => void = () => {},
  ) {}

  close(): void {
    this.closed = true;
  }

  /** `{"type":"changes", ...}`: one tick's batch. */
  handleChanges(changes: Change[]): void {
    this.store.batch(() => {
      for (const change of changes) this.applyChange(change);
    });
  }

  private applyChange(change: Change): void {
    if (change.deleted === true) {
      this.store.remove(change.id);
      return;
    }
    if (typeof change.version !== 'number') return;
    const version = change.version;
    const cached = this.store.get(change.id);
    const hasPatch = typeof change.patch === 'object' && change.patch !== null;
    const children = Array.isArray(change.children) ? change.children : undefined;
    if (!cached) {
      if (hasPatch) {
        // A created resource: its first version with its state as the patch.
        this.store.put({
          id: change.id,
          type: change.type ?? change.id.split(':')[0] ?? '',
          version,
          children: children ?? [],
          state: change.patch as Record<string, unknown>,
        });
      } else {
        void this.refresh(change.id);
      }
      return;
    }
    switch (decideChange(cached.version, version, hasPatch)) {
      case 'apply':
        this.store.applyPatch(
          change.id,
          version,
          change.patch as Record<string, unknown>,
          children,
        );
        break;
      case 'fetch':
        this.store.markStale(change.id);
        void this.refresh(change.id);
        break;
      case 'ignore':
        break;
    }
  }

  /** `{"type":"digest", "versions": {...}}`: the server's versions of the area's resources. */
  handleDigest(versions: Record<string, number>): void {
    this.store.batch(() => {
      // The digest is authoritative for what exists: drop what it does not list.
      for (const id of this.descendantsOf(this.areaId)) {
        if (!(id in versions)) this.store.remove(id);
      }
      for (const [id, version] of Object.entries(versions)) {
        if (typeof version !== 'number') continue;
        if (decideDigestEntry(this.store.get(id)?.version, version) === 'fetch') {
          void this.refresh(id);
        }
      }
    });
  }

  /** Cached ids below the area (its children, their children, ...), not the area itself. */
  private descendantsOf(rootId: string): string[] {
    const out: string[] = [];
    const seen = new Set<string>([rootId]);
    const queue = [rootId];
    while (queue.length > 0) {
      const current = this.store.get(queue.shift() as string);
      for (const childId of current?.children ?? []) {
        if (seen.has(childId)) continue;
        seen.add(childId);
        if (this.store.get(childId)) out.push(childId);
        queue.push(childId);
      }
    }
    return out;
  }

  /**
   * GET a resource into the store. For the area itself, children we don't
   * know yet are fetched afterwards. Concurrent requests for one id are
   * coalesced into one more fetch after the running one.
   */
  async refresh(id: string): Promise<void> {
    const running = this.inFlight.get(id);
    if (running) {
      running.again = true;
      return;
    }
    const slot = { again: false };
    this.inFlight.set(id, slot);
    try {
      do {
        slot.again = false;
        const cachedVersion = this.store.get(id)?.version;
        const fetched = await this.fetchResource(id, cachedVersion);
        if (this.closed) return;
        if (fetched) {
          this.store.put(fetched);
        } else {
          this.store.clearStale(id); // 304: what we have is current
        }
        if (id === this.areaId) {
          const area = this.store.get(id);
          for (const childId of area?.children ?? []) {
            if (!this.store.get(childId)) void this.refresh(childId);
          }
        }
      } while (slot.again);
    } catch (err) {
      if (!this.closed) this.onError(err instanceof Error ? err.message : String(err));
    } finally {
      this.inFlight.delete(id);
    }
  }
}
