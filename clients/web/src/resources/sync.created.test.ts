import { describe, expect, it, vi } from 'vitest';
import { ResourceStore, type Resource } from './cache.ts';
import { AreaSync } from './sync.ts';

const res = (
  id: string,
  version: number,
  state: Record<string, unknown> = {},
  children: string[] = [],
): Resource => ({
  id,
  type: id.split(':')[0] ?? '',
  version,
  children,
  state,
});

const AREA = 'area:a';

function setup() {
  const store = new ResourceStore();
  const fetchResource = vi.fn(async () => undefined as Resource | undefined);
  const sync = new AreaSync(AREA, store, fetchResource);
  return { store, sync, fetchResource };
}

describe('AreaSync created tests', () => {
  it("created: unknown id 'entity:n' version 1 with patch {position:{x:1,y:2}} is stored with version 1, that state, children [] and no fetch", async () => {
    const { store, sync, fetchResource } = setup();
    await sync.handleChanges([
      {
        id: 'entity:n',
        version: 1,
        patch: { position: { x: 1, y: 2 } },
      },
    ]);

    expect(store.get('entity:n')).toEqual(res('entity:n', 1, { position: { x: 1, y: 2 } }, []));
    expect(fetchResource).not.toHaveBeenCalled();
  });

  it('created with children stores those children', async () => {
    const { store, sync, fetchResource } = setup();
    await sync.handleChanges([
      {
        id: 'entity:n',
        version: 1,
        patch: { position: { x: 1, y: 2 } },
        children: ['item:i'],
      },
    ]);

    expect(store.get('entity:n')).toEqual(
      res('entity:n', 1, { position: { x: 1, y: 2 } }, ['item:i']),
    );
    expect(fetchResource).not.toHaveBeenCalled();
  });

  it("unknown id without patch (version 1) triggers fetchResource with ('entity:n', undefined)", async () => {
    const { sync, fetchResource } = setup();
    fetchResource.mockResolvedValueOnce(res('entity:n', 1, { position: { x: 1, y: 2 } }));

    await sync.handleChanges([
      {
        id: 'entity:n',
        version: 1,
      },
    ]);

    expect(fetchResource).toHaveBeenCalledWith('entity:n', undefined);
  });

  it("children replaces the list: store.put(res('entity:o',1,{}, ['item:1'])), change {id:'entity:o',version:2,patch:{a:1},children:['item:2','item:3']} -> children ['item:2','item:3'] and version 2", async () => {
    const { store, sync } = setup();

    // Setup initial state
    store.put(res('entity:o', 1, {}, ['item:1']));

    await sync.handleChanges([
      {
        id: 'entity:o',
        version: 2,
        patch: { a: 1 },
        children: ['item:2', 'item:3'],
      },
    ]);

    expect(store.get('entity:o')).toEqual(res('entity:o', 2, { a: 1 }, ['item:2', 'item:3']));
  });

  it("stale change with children changes nothing: store has entity:o at version 5 with children ['item:1']; change version 4 with patch and children ['item:9'] -> children still ['item:1'] and version 5", async () => {
    const { store, sync } = setup();

    // Setup initial state
    store.put(res('entity:o', 5, {}, ['item:1']));

    await sync.handleChanges([
      {
        id: 'entity:o',
        version: 4,
        patch: { a: 1 },
        children: ['item:9'],
      },
    ]);

    // Should remain unchanged
    expect(store.get('entity:o')).toEqual(res('entity:o', 5, {}, ['item:1']));
  });

  it("deleted drops the resource: change {id:'entity:o',type:'entity',deleted:true}", async () => {
    const { store, sync } = setup();

    // Setup initial state
    store.put(res('entity:o', 1, {}, []));

    await sync.handleChanges([
      {
        id: 'entity:o',
        type: 'entity',
        deleted: true,
      },
    ]);

    expect(store.get('entity:o')).toBeUndefined();
  });

  it('deleting an unknown id does not throw and fetches nothing', async () => {
    const { sync, fetchResource } = setup();

    // This should not throw or make any fetch calls
    await sync.handleChanges([
      {
        id: 'entity:o',
        type: 'entity',
        deleted: true,
      },
    ]);

    expect(fetchResource).not.toHaveBeenCalled();
  });

  it("batch consistency, both orders: area res(AREA,1,{},['entity:a','entity:b']) and res('entity:b',1) in the store; batch [{id:AREA,version:2,patch:{},children:['entity:a']},{id:'entity:b',type:'entity',deleted:true}] -> area children ['entity:a'], entity:b undefined; the same batch reversed gives the same result", async () => {
    const { store, sync } = setup();

    // Setup initial state
    store.put(res(AREA, 1, {}, ['entity:a', 'entity:b']));
    store.put(res('entity:b', 1, {}, []));

    // Test first order
    await sync.handleChanges([
      {
        id: AREA,
        version: 2,
        patch: {},
        children: ['entity:a'],
      },
      {
        id: 'entity:b',
        type: 'entity',
        deleted: true,
      },
    ]);

    expect(store.get(AREA)?.children).toEqual(['entity:a']);
    expect(store.get('entity:b')).toBeUndefined();

    // Reset and test reversed order
    const { store: store2, sync: sync2 } = setup();
    store2.put(res(AREA, 1, {}, ['entity:a', 'entity:b']));
    store2.put(res('entity:b', 1, {}, []));

    await sync2.handleChanges([
      {
        id: 'entity:b',
        type: 'entity',
        deleted: true,
      },
      {
        id: AREA,
        version: 2,
        patch: {},
        children: ['entity:a'],
      },
    ]);

    expect(store2.get(AREA)?.children).toEqual(['entity:a']);
    expect(store2.get('entity:b')).toBeUndefined();
  });

  it('batch notifies once when changes are applied in a batch', async () => {
    const { store, sync } = setup();

    // Setup initial state
    store.put(res(AREA, 1, {}, ['entity:a', 'entity:b']));
    store.put(res('entity:b', 1, {}, []));

    // Add a listener to track calls
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    await sync.handleChanges([
      {
        id: AREA,
        version: 2,
        patch: {},
        children: ['entity:a'],
      },
      {
        id: 'entity:b',
        type: 'entity',
        deleted: true,
      },
    ]);

    // Should have been called exactly once due to batch behavior
    expect(listener).toHaveBeenCalledTimes(1);

    unsubscribe();
  });
});
