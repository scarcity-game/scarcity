import type { Resource } from '../resources/cache.ts';
import type { LogEntry } from '../log/types.ts';

/**
 * Applies a log entry to a resource map, mutating the map in place.
 * @param resources The map of resources to mutate
 * @param entry The log entry to apply
 */
export function applyEntry(resources: Map<string, Resource>, entry: LogEntry): void {
  let hasSnapshot = false;
  const areaIds: string[] = [];

  for (const change of entry.changes) {
    switch (change.op) {
      case 'snapshot': {
        if (!hasSnapshot) {
          // Clear the map on first snapshot
          resources.clear();
          hasSnapshot = true;
        }

        // Add the resource with default values if not provided
        const resource: Resource = {
          id: change.id,
          type: change.type,
          version: change.version ?? 0,
          children: [],
          state: change.state ?? {},
        };

        resources.set(change.id, resource);

        // Track area IDs for later use in children assignment
        areaIds.push(change.id);
        break;
      }

      case 'changed': {
        if (resources.has(change.id)) {
          const existing = resources.get(change.id)!;
          const updated: Resource = {
            ...existing,
            state: change.state ?? existing.state,
            version: typeof change.version === 'number' ? change.version : existing.version
          };
          resources.set(change.id, updated);
        }
        break;
      }

      case 'created': {
        const newResource: Resource = {
          id: change.id,
          type: change.type,
          version: change.version ?? 0,
          children: [],
          state: change.state ?? {},
        };

        resources.set(change.id, newResource);

        // If parent is specified and exists, update parent's children
        if (change.parent && resources.has(change.parent)) {
          const parent = resources.get(change.parent)!;
          if (!parent.children.includes(change.id)) {
            const updatedParent: Resource = {
              ...parent,
              children: [...parent.children, change.id]
            };
            resources.set(change.parent, updatedParent);
          }
        }
        break;
      }

      case 'destroyed': {
        // First read the resource to be destroyed
        const gone = resources.get(change.id);

        // Remove the resource from the map
        resources.delete(change.id);

        // Recursively delete all children of the destroyed resource first
        if (gone) {
          for (const childId of gone.children ?? []) {
            applyEntry(resources, { seq: 0, area: '', time: 0, kind: 'destroyed', cause: null, root: 0, resource: null, draws: [], changes: [{ id: childId, type: 'entity', op: 'destroyed' }] });
          }
        }

        // Remove this id from all parents' children arrays
        for (const [id, resource] of resources.entries()) {
          if (resource.children.includes(change.id)) {
            const updatedChildren = resource.children.filter(childId => childId !== change.id);
            const updatedResource: Resource = {
              ...resource,
              children: updatedChildren
            };
            resources.set(id, updatedResource);
          }
        }
        break;
      }

      default:
        // Ignore other ops
        break;
    }
  }

  // After processing all snapshot changes, set children for area resources
  if (hasSnapshot) {
    for (const change of entry.changes) {
      if (change.type === 'area' && change.op === 'snapshot') {
        const resource = resources.get(change.id)!;
        const updatedResource: Resource = {
          ...resource,
          children: areaIds.filter(id => id !== change.id && resources.get(id)?.type !== 'area')
        };
        resources.set(change.id, updatedResource);
      }
    }
  }
}

/**
 * A replay system that can apply log entries to a snapshot and seek through time.
 */
export class Replay {
  private readonly entries: readonly LogEntry[];
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

    // Calculate effect roots
    this._effectRoots = [];
    const actionStartEntries: Map<number, LogEntry> = new Map();

    // First pass: collect all action-start entries with null cause
    for (let i = 0; i < this.entries.length; i++) {
      const entry = this.entries[i]!;
      if (entry.kind === 'action-start' && entry.cause === null) {
        actionStartEntries.set(entry.root, entry);
      }
    }

    // Second pass: find entries with effects that match action-start roots
    const rootsWithEffects = new Set<number>();
    for (const entry of this.entries) {
      if (entry.kind === 'effect') {
        rootsWithEffects.add(entry.root);
      }
    }

    for (let i = 0; i < this.entries.length; i++) {
      const entry = this.entries[i]!;
      if (entry.kind === 'action-start' && entry.cause === null && rootsWithEffects.has(entry.root)) {
        this._effectRoots.push(i);
      }
    }

    // Sort effect roots in ascending order
    this._effectRoots.sort((a, b) => a - b);
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
    // Find the last entry with seq <= seq
    let i = -1;
    for (let j = 0; j < this.entries.length; j++) {
      if (this.entries[j]!.seq <= seq) {
        i = j;
      } else {
        break;
      }
    }
    this.seekIndex(i);
  }

  seekTime(t: number): void {
    // Find the last entry with time <= t
    let i = -1;
    for (let j = 0; j < this.entries.length; j++) {
      if (this.entries[j]!.time <= t) {
        i = j;
      } else {
        break;
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
    // Find the largest value in effectRoots that is < this.index
    for (let i = this._effectRoots.length - 1; i >= 0; i--) {
      if (this._effectRoots[i]! < this._index) {
        return this._effectRoots[i]!;
      }
    }
    return null;
  }

  nextEffectRoot(): number | null {
    // Find the smallest value in effectRoots that is > this.index
    for (const rootIndex of this._effectRoots) {
      if (rootIndex > this._index) {
        return rootIndex;
      }
    }
    return null;
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