import type { Resource, ResourceStore } from './cache.ts';
import { decideChange } from './versionRule.ts';
import { checksumHex, versionChecksum } from './versionChecksum.ts';
import type { Versions } from './worldApi.ts';

/**
 * One item of a `changes` frame (docs/resources.md, "Change notifications"): always `resourceKind`
 * and `id`, then
 * - an update: `version` and any of `set`, `unset`, `childrenAdded`, `childrenRemoved`, applied to
 *   the cached `version - 1`;
 * - created: `version` and `created: true`; the resource is fetched;
 * - deleted: `deleted: true`, no version;
 * - a stub: `version` only, an update too big to send; the resource is fetched.
 */
export interface Change {
  id: string;
  resourceKind?: string;
  version?: number;
  created?: boolean;
  deleted?: boolean;
  set?: Record<string, unknown>;
  unset?: string[];
  childrenAdded?: string[];
  childrenRemoved?: string[];
}

/** A `changes` frame's batch: the deltas, and where it sits in the area's log (the logSeq chain). */
export interface ChangeBatch {
  changes: Change[];
  fromLogSeq?: number;
  logSeq?: number;
  part?: number;
  parts?: number;
}

const DELTA_KEYS = ['set', 'unset', 'childrenAdded', 'childrenRemoved'] as const;

function isUpdate(change: Change): boolean {
  return DELTA_KEYS.some((key) => key in change);
}

function isLastPart({ part, parts }: ChangeBatch): boolean {
  return part === undefined || parts === undefined || part >= parts;
}

/** The cached resource with `change` (an update to its next version) applied. */
export function applyUpdate(cached: Resource, change: Change, version: number): Resource {
  const next: Resource = { ...cached, ...(change.set ?? {}), id: cached.id, version };
  for (const key of change.unset ?? []) delete next[key];
  const removed = new Set(change.childrenRemoved ?? []);
  const children = cached.children.filter((id) => !removed.has(id));
  for (const id of change.childrenAdded ?? []) if (!children.includes(id)) children.push(id);
  next.children = children;
  return next;
}

/** GETs a resource; `cachedVersion` is sent as If-None-Match, a 304 gives undefined. */
export type FetchResource = (id: string, cachedVersion?: number) => Promise<Resource | undefined>;

/** What the sync reads from world: one resource, an area's children (every page), its versions. */
export interface AreaApi {
  get: FetchResource;
  children(id: string): Promise<Resource[]>;
  versions(id: string): Promise<Versions>;
}

/**
 * Keeps a ResourceStore in step with the server for one area (docs/websocket.md): it loads the area
 * by GET, applies the `changes` frames' deltas, follows the logSeq chain, compares the periodic
 * `checksum`, and on a gap or a mismatch resyncs from `…/versions`. A `reload` loads the area afresh.
 */
export class AreaSync {
  private inFlight = new Map<string, { again: boolean }>();
  private closed = false;
  private loaded = false;
  private resyncing = false;
  /** The checksum frame that came before the load finished, checked once it has. */
  private pendingChecksum: { checksum: string; logSeq?: number } | null = null;
  /** The area's log head as far as this sync has followed it; undefined until known. */
  private lastLogSeq: number | undefined;
  /** While a split batch arrives: the `fromLogSeq` its later parts repeat. */
  private partFrom: number | undefined;

  constructor(
    private readonly areaId: string,
    private readonly store: ResourceStore,
    private readonly api: AreaApi,
    private readonly onError: (message: string) => void = () => {},
  ) {}

  close(): void {
    this.closed = true;
  }

  /** The log head the sync has followed to; for tests and the log panel. */
  get logSeq(): number | undefined {
    return this.lastLogSeq;
  }

  /** The initial load, and a reload: GET the area, then every page of its children. */
  async load(): Promise<void> {
    this.loaded = false;
    const area = await this.api.get(this.areaId);
    const children = await this.api.children(this.areaId);
    if (this.closed) return;
    this.store.batch(() => {
      if (area) this.store.put(area);
      for (const child of children) this.store.put(child);
    });
    this.loaded = true;
    const pending = this.pendingChecksum;
    this.pendingChecksum = null;
    if (pending) this.handleChecksum(pending.checksum, pending.logSeq);
  }

  /** `{"type":"changes", ...}`: one advance's batch, or one part of it. */
  handleChanges(batch: ChangeBatch): void {
    const missed = this.followChain(batch);
    this.store.batch(() => {
      for (const change of batch.changes) this.applyChange(change);
    });
    if (missed) void this.resync();
  }

  /** `{"type":"checksum", ...}`: resync if what we hold isn't what the server holds. */
  handleChecksum(checksum: string, logSeq?: number): void {
    if (!this.loaded) {
      this.pendingChecksum = { checksum, logSeq };
      return;
    }
    // A GET in flight changes what we hold; the next checksum (about 30 s) checks again.
    if (this.inFlight.size > 0 || this.resyncing) return;
    if (this.behind(logSeq) || checksum !== this.checksum()) {
      void this.resync();
      return;
    }
    this.advanceTo(logSeq);
  }

  /** Whether the server's log head is past the one we followed to: we missed a batch. */
  private behind(logSeq: number | undefined): boolean {
    return logSeq !== undefined && this.lastLogSeq !== undefined && logSeq > this.lastLogSeq;
  }

  private advanceTo(logSeq: number | undefined): void {
    if (logSeq !== undefined) this.lastLogSeq = Math.max(this.lastLogSeq ?? logSeq, logSeq);
  }

  /** `{"type":"reload", ...}`: drop the area and everything under it, and load it afresh. */
  async handleReload(logSeq?: number): Promise<void> {
    this.store.batch(() => {
      for (const id of this.descendantsOf(this.areaId)) this.store.remove(id);
      this.store.remove(this.areaId);
    });
    this.lastLogSeq = logSeq;
    this.partFrom = undefined;
    try {
      await this.load();
    } catch (err) {
      if (!this.closed) this.onError(err instanceof Error ? err.message : String(err));
    }
  }

  /** The checksum of the area and everything under it that we hold. */
  checksum(): string {
    const ids = [this.areaId, ...this.descendantsOf(this.areaId)];
    const versions = ids.flatMap((id) => {
      const r = this.store.get(id);
      return r ? [[id, r.version] as const] : [];
    });
    return checksumHex(versionChecksum(versions));
  }

  /**
   * Moves the chain on by `batch`; true if a batch was missed (its `fromLogSeq` is past ours). A split
   * batch's later parts repeat its `fromLogSeq`; the chain moves on after the last part.
   */
  private followChain(batch: ChangeBatch): boolean {
    if (batch.logSeq === undefined) return false;
    const continues = this.partFrom !== undefined && batch.fromLogSeq === this.partFrom;
    const missed = !continues && this.startsPastHead(batch.fromLogSeq);
    const last = isLastPart(batch);
    this.partFrom = last ? undefined : batch.fromLogSeq;
    if (last) this.advanceTo(batch.logSeq);
    return missed;
  }

  private startsPastHead(fromLogSeq: number | undefined): boolean {
    return fromLogSeq !== undefined && this.lastLogSeq !== undefined && fromLogSeq > this.lastLogSeq;
  }

  private applyChange(change: Change): void {
    if (change.deleted === true) {
      this.removeWithDescendants(change.id);
    } else if (typeof change.version !== 'number') {
      return;
    } else if (change.created === true) {
      if (!this.store.get(change.id)) void this.refresh(change.id);
    } else {
      this.applyVersioned(change, change.version);
    }
  }

  // An update or a stub, by the version rule.
  private applyVersioned(change: Change, version: number): void {
    const cached = this.store.get(change.id);
    switch (decideChange(cached?.version, version, isUpdate(change))) {
      case 'apply':
        this.store.put(applyUpdate(cached as Resource, change, version));
        break;
      case 'fetch':
        this.store.markStale(change.id);
        void this.refresh(change.id);
        break;
      case 'ignore':
        break;
    }
  }

  // Removes cached descendants first, then the resource itself.
  private removeWithDescendants(id: string): void {
    for (const descendantId of this.descendantsOf(id)) {
      this.store.remove(descendantId);
    }
    this.store.remove(id);
  }

  /**
   * Brings what we hold in line with `…/versions`: GET what differs or is missing, drop what isn't
   * listed, and take its `logSeq` as the chain's head. One at a time; a second request while one
   * runs is dropped (the next checksum checks again).
   */
  async resync(): Promise<void> {
    if (this.resyncing || this.closed) return;
    this.resyncing = true;
    try {
      const server = await this.api.versions(this.areaId);
      if (this.closed) return;
      this.matchVersions(server.versions);
      if (server.logSeq !== undefined) this.lastLogSeq = server.logSeq;
      this.partFrom = undefined;
    } catch (err) {
      if (!this.closed) this.onError(err instanceof Error ? err.message : String(err));
    } finally {
      this.resyncing = false;
    }
  }

  /** Drops what `versions` doesn't list, and GETs what differs from it or is missing. */
  private matchVersions(versions: Record<string, number>): void {
    this.store.batch(() => {
      for (const id of this.descendantsOf(this.areaId)) {
        if (!(id in versions)) this.store.remove(id);
      }
    });
    for (const [id, version] of Object.entries(versions)) {
      if (this.store.get(id)?.version !== version) void this.refresh(id);
    }
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
        if (!(await this.fetchInto(id))) return;
      } while (slot.again);
    } catch (err) {
      if (!this.closed) this.onError(err instanceof Error ? err.message : String(err));
    } finally {
      this.inFlight.delete(id);
    }
  }

  // One GET of a resource into the store. False when the sync was closed meanwhile.
  private async fetchInto(id: string): Promise<boolean> {
    const cachedVersion = this.store.get(id)?.version;
    const fetched = await this.api.get(id, cachedVersion);
    if (this.closed) return false;
    if (fetched) {
      this.store.put(fetched);
    } else {
      this.store.clearStale(id); // 304: what we have is current
    }
    if (id === this.areaId) this.refreshUnknownChildren(id);
    return true;
  }

  private refreshUnknownChildren(id: string): void {
    for (const childId of this.store.get(id)?.children ?? []) {
      if (!this.store.get(childId)) void this.refresh(childId);
    }
  }
}
