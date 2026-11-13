import { describe, expect, it, vi } from 'vitest';
import { ResourceStore, type Resource } from './cache.ts';
import { AreaSync } from './sync.ts';

const res = (
  id: string,
  version: number,
  state: Record<string, unknown> = {},
  children: string[] = [],
): Resource => ({ id, type: id.split(':')[0] ?? '', version, children, state });

const AREA = 'area:a';

function setup() {
  const store = new ResourceStore();
  const fetchResource = vi.fn(async () => undefined as Resource | undefined);
  const sync = new AreaSync(AREA, store, fetchResource);
  return { store, sync, fetchResource };
}

describe('AreaSync.handleDigest', () => {
  it('drops a cached entity that is a child of the area but missing from the digest', () => {
    const { store, sync } = setup();
    store.put(res(AREA, 1, {}, ['entity:1', 'entity:2']));
    store.put(res('entity:1', 1));
    store.put(res('entity:2', 1));

    // Digest only lists the area and entity:1
    sync.handleDigest({ [AREA]: 1, 'entity:1': 1 });

    // entity:2 should be removed from store
    expect(store.get('entity:2')).toBeUndefined();
    // entity:1 should still exist
    expect(store.get('entity:1')).toBeDefined();
  });

  it('drops items below a dropped entity too', () => {
    const { store, sync } = setup();
    store.put(res(AREA, 1, {}, ['entity:2']));
    store.put(res('entity:2', 1, {}, ['item:1']));
    store.put(res('item:1', 1));

    // Digest doesn't list entity:2 or item:1
    sync.handleDigest({ [AREA]: 1 });

    // Both entity:2 and item:1 should be removed from store
    expect(store.get('entity:2')).toBeUndefined();
    expect(store.get('item:1')).toBeUndefined();
  });

  it('fetches an unknown id listed in the digest', () => {
    const { store, sync, fetchResource } = setup();
    store.put(res(AREA, 1, {}, ['entity:1']));
    store.put(res('entity:1', 1));

    // Digest lists entity:3 which is not cached
    sync.handleDigest({ [AREA]: 1, 'entity:1': 1, 'entity:3': 1 });

    // Should call fetchResource for entity:3
    expect(fetchResource).toHaveBeenCalledWith('entity:3', undefined);
  });

  it('leaves resources of another area untouched', () => {
    const { store, sync } = setup();
    store.put(res(AREA, 1, {}, ['entity:1']));
    store.put(res('entity:1', 1));
    // Add a resource from another area
    store.put(res('area:other', 1, {}, ['entity:x']));
    store.put(res('entity:x', 1));

    // Digest only lists the area and entity:1
    sync.handleDigest({ [AREA]: 1, 'entity:1': 1 });

    // Both resources should still exist
    expect(store.get('area:other')).toBeDefined();
    expect(store.get('entity:x')).toBeDefined();
  });

  it('never drops the area itself even if the digest does not list it', () => {
    const { store, sync } = setup();
    store.put(res(AREA, 1, {}, ['entity:1']));
    store.put(res('entity:1', 1));

    // Digest doesn't list the area
    sync.handleDigest({ 'entity:1': 1 });

    // Area should still exist
    expect(store.get(AREA)).toBeDefined();
  });
});
