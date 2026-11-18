import type { Resource } from '../resources/cache.ts';
import type { LogChange, LogEntry } from '../log/types.ts';

type Resources = Map<string, Resource>;

/** A resource as a snapshot or a creation carries it: defaults for what the change does not give. */
function newResource(change: LogChange): Resource {
  return {
    ...change.state,
    id: change.id,
    resourceKind: change.type,
    version: change.version ?? 0,
    children: [],
  };
}

function applyChanged(resources: Resources, change: LogChange): void {
  const existing = resources.get(change.id);
  if (!existing) return;
  resources.set(change.id, {
    ...(change.state ?? existing),
    id: existing.id,
    resourceKind: existing.resourceKind,
    children: existing.children,
    version: typeof change.version === 'number' ? change.version : existing.version,
  });
}

function applyCreated(resources: Resources, change: LogChange): void {
  resources.set(change.id, newResource(change));
  // If parent is specified and exists, update parent's children
  const parent = change.parent ? resources.get(change.parent) : undefined;
  if (change.parent && parent && !parent.children.includes(change.id)) {
    resources.set(change.parent, { ...parent, children: [...parent.children, change.id] });
  }
}

/** Removes a resource, its children first (recursively), and its id from every parent. */
function destroy(resources: Resources, id: string): void {
  const gone = resources.get(id);
  resources.delete(id);
  for (const childId of gone?.children ?? []) destroy(resources, childId);
  for (const [otherId, resource] of resources.entries()) {
    if (resource.children.includes(id)) {
      resources.set(otherId, {
        ...resource,
        children: resource.children.filter((childId) => childId !== id),
      });
    }
  }
}

/** After the snapshot changes, the area resources get the other snapshotted resources as children. */
function assignAreaChildren(resources: Resources, entry: LogEntry, snapshotIds: string[]): void {
  for (const change of entry.changes) {
    if (change.type !== 'area' || change.op !== 'snapshot') continue;
    const resource = resources.get(change.id)!;
    resources.set(change.id, {
      ...resource,
      children: snapshotIds.filter(
        (id) => id !== change.id && resources.get(id)?.resourceKind !== 'area',
      ),
    });
  }
}

/**
 * Applies a log entry to a resource map, mutating the map in place.
 * @param resources The map of resources to mutate
 * @param entry The log entry to apply
 */
export function applyEntry(resources: Resources, entry: LogEntry): void {
  const snapshotIds: string[] = [];
  for (const change of entry.changes) {
    switch (change.op) {
      case 'snapshot':
        // Clear the map on first snapshot
        if (snapshotIds.length === 0) resources.clear();
        resources.set(change.id, newResource(change));
        snapshotIds.push(change.id);
        break;
      case 'changed':
        applyChanged(resources, change);
        break;
      case 'created':
        applyCreated(resources, change);
        break;
      case 'destroyed':
        destroy(resources, change.id);
        break;
      default:
        // Ignore other ops
        break;
    }
  }
  if (snapshotIds.length > 0) assignAreaChildren(resources, entry, snapshotIds);
}

/** Indexes of the entries that start a tree (an `intent`, or an action a behaviour started) whose tree has an effect. */
function findEffectRoots(entries: readonly LogEntry[]): number[] {
  const rootsWithEffects = new Set<number>();
  for (const entry of entries) {
    if (entry.kind === 'effect') rootsWithEffects.add(entry.root);
  }
  const roots: number[] = [];
  entries.forEach((entry, i) => {
    const startsTree = entry.kind === 'intent' || entry.kind === 'action-start';
    if (startsTree && entry.cause === null && rootsWithEffects.has(entry.root)) roots.push(i);
  });
  return roots;
}

/**
 * A replay system that can apply log entries to a snapshot and seek through time.
 */
export class Replay {
  readonly entries: readonly LogEntry[];
  private _index: number;
  private workingMap: Map<string, Resource>;
  private _resources: ReadonlyMap<string, Resource>;
  private readonly _effectRoots: number[];

  constructor(readonly snapshot: LogEntry, entries: readonly LogEntry[]) {
    // Create a copy of entries sorted by seq ascending
    this.entries = [...entries].sort((a, b) => a.seq - b.seq);

    // Initialize with the snapshot
    this._index = -1;
    this.workingMap = new Map();
    applyEntry(this.workingMap, snapshot);

    // Create initial resources instance
    this._resources = new Map(this.workingMap);

    this._effectRoots = findEffectRoots(this.entries);
  }

  get index(): number {
    return this._index;
  }

  get length(): number {
    return this.entries.length;
  }

  get seq(): number {
    if (this._index === -1) {
      return this.snapshot.seq;
    }
    return this.entries[this._index]!.seq;
  }

  get time(): number {
    if (this._index === -1) {
      return this.snapshot.time;
    }
    return this.entries[this._index]!.time;
  }

  get endTime(): number {
    if (this.entries.length === 0) {
      return this.snapshot.time;
    }
    return this.entries[this.entries.length - 1]!.time;
  }

  get resources(): ReadonlyMap<string, Resource> {
    return this._resources;
  }

  seekIndex(i: number): void {
    // Clamp i to [-1, length-1]
    i = Math.max(-1, Math.min(i, this.entries.length - 1));

    if (i === this._index) {
      return; // No change needed
    }

    // Reset working map based on index
    if (i < this._index) {
      // Go backwards: rebuild from scratch
      this.workingMap = new Map();
      applyEntry(this.workingMap, this.snapshot);

      for (let j = 0; j <= i; j++) {
        applyEntry(this.workingMap, this.entries[j]!);
      }
    } else {
      // Go forwards: incremental application
      for (let j = this._index + 1; j <= i; j++) {
        applyEntry(this.workingMap, this.entries[j]!);
      }
    }

    this._index = i;
    // Create new resources instance to prevent external mutation
    this._resources = new Map(this.workingMap);
  }

  seekSeq(seq: number): void {
    // Find the last entry with seq <= seq using binary search
    let left = 0;
    let right = this.entries.length - 1;
    let i = -1;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);
      if (this.entries[mid]!.seq <= seq) {
        i = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    this.seekIndex(i);
  }

  seekTime(t: number): void {
    // Find the last entry with time <= t using binary search
    let left = 0;
    let right = this.entries.length - 1;
    let i = -1;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);
      if (this.entries[mid]!.time <= t) {
        i = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    // For entries with the same time, we need to process all of them in seq order
    if (i >= 0) {
      const time = this.entries[i]!.time;
      while (i + 1 < this.entries.length && this.entries[i + 1]!.time === time) {
        i++;
      }
    }

    this.seekIndex(i);
  }

  rebuildAt(i: number): ReadonlyMap<string, Resource> {
    // Create a fresh map from snapshot plus entries 0..i (i = -1: snapshot only)
    const newMap = new Map<string, Resource>();
    applyEntry(newMap, this.snapshot);

    for (let j = 0; j <= i; j++) {
      applyEntry(newMap, this.entries[j]!);
    }

    return newMap;
  }

  get effectRoots(): number[] {
    return this._effectRoots;
  }

  prevEffectRoot(): number | null {
    // Find the largest value in effectRoots that is < this.index using binary search
    let left = 0;
    let right = this._effectRoots.length - 1;
    let result = -1;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);
      if (this._effectRoots[mid]! < this._index) {
        result = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    return result === -1 ? null : this._effectRoots[result]!;
  }

  nextEffectRoot(): number | null {
    // Find the smallest value in effectRoots that is > this.index using binary search
    let left = 0;
    let right = this._effectRoots.length - 1;
    let result = this._effectRoots.length;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);
      if (this._effectRoots[mid]! > this._index) {
        result = mid;
        right = mid - 1;
      } else {
        left = mid + 1;
      }
    }

    return result === this._effectRoots.length ? null : this._effectRoots[result]!;
  }

  areaEntities(areaId: string): Resource[] {
    const areaResource = this.resources.get(areaId);
    if (!areaResource) {
      return [];
    }

    // Look up all children in the resources map, skipping missing ones
    return areaResource.children
      .map(childId => this.resources.get(childId))
      .filter((resource): resource is Resource => resource !== undefined);
  }
}