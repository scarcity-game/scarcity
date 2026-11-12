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

function setup(server: Record<string, Resource | 'same'>) {
  const store = new ResourceStore();
  const calls: [string, number | undefined][] = [];
  const fetchResource = vi.fn(async (id: string, v?: number) => {
    calls.push([id, v]);
    const r = server[id];
    if (!r) throw new Error(`404 ${id}`);
    return r === 'same' ? undefined : r;
  });
  const onError = vi.fn();
  const sync = new AreaSync(AREA, store, fetchResource, onError);
  const settle = () => new Promise((r) => setTimeout(r, 0));
  return { store, sync, calls, onError, settle };
}

describe('ResourceStore', () => {
  it('applies a patch by replacing the state and keeps children', () => {
    const s = new ResourceStore();
    s.put(res('entity:1', 1, { a: 1, b: 2 }, ['item:1']));
    s.applyPatch('entity:1', 2, { a: 9 });
    expect(s.get('entity:1')).toEqual(res('entity:1', 2, { a: 9 }, ['item:1']));
  });

  it('does not let an older fetched copy overwrite a newer one', () => {
    const s = new ResourceStore();
    s.put(res('entity:1', 5, { a: 5 }));
    expect(s.put(res('entity:1', 4, { a: 4 }))).toBe(false);
    expect(s.get('entity:1')?.version).toBe(5);
  });

  it('marks stale and clears it on the next put; notifies subscribers', () => {
    const s = new ResourceStore();
    s.put(res('entity:1', 1));
    const listener = vi.fn();
    s.subscribe(listener);
    s.markStale('entity:1');
    expect(s.isStale('entity:1')).toBe(true);
    s.put(res('entity:1', 3));
    expect(s.isStale('entity:1')).toBe(false);
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('AreaSync.handleChanges', () => {
  it('applies a patch at cached+1 without a GET', async () => {
    const { store, sync, calls, settle } = setup({});
    store.put(res('entity:o', 4, { position: { x: 1, y: 1 } }));
    sync.handleChanges([
      {
        id: 'entity:o',
        type: 'entity',
        version: 5,
        patch: { position: { x: 2, y: 2 } },
      },
    ]);
    await settle();
    expect(store.get('entity:o')).toMatchObject({
      version: 5,
      state: { position: { x: 2, y: 2 } },
    });
    expect(calls).toEqual([]);
  });

  it('ignores duplicate and old versions', async () => {
    const { store, sync, calls, settle } = setup({});
    store.put(res('entity:o', 4, { n: 4 }));
    sync.handleChanges([
      { id: 'entity:o', version: 4, patch: { n: 'dup' } },
      { id: 'entity:o', version: 2, patch: { n: 'old' } },
    ]);
    await settle();
    expect(store.get('entity:o')?.state).toEqual({ n: 4 });
    expect(calls).toEqual([]);
  });

  it('on a gap marks stale and GETs with the cached version', async () => {
    const { store, sync, calls, settle } = setup({
      'entity:o': res('entity:o', 9, { n: 9 }),
    });
    store.put(res('entity:o', 4, { n: 4 }));
    sync.handleChanges([{ id: 'entity:o', version: 8, patch: { n: 8 } }]);
    expect(store.isStale('entity:o')).toBe(true);
    await settle();
    expect(calls).toEqual([['entity:o', 4]]);
    expect(store.get('entity:o')?.version).toBe(9);
    expect(store.isStale('entity:o')).toBe(false);
  });

  it('keeps the cached copy on 304', async () => {
    const { store, sync, settle } = setup({ 'entity:o': 'same' });
    store.put(res('entity:o', 4, { n: 4 }));
    sync.handleChanges([{ id: 'entity:o', version: 7 }]);
    await settle();
    expect(store.get('entity:o')?.state).toEqual({ n: 4 });
    expect(store.isStale('entity:o')).toBe(false);
  });

  it('GETs an uncached resource and does not trust its patch', async () => {
    const { store, sync, calls, settle } = setup({
      'entity:new': res('entity:new', 1, { real: true }),
    });
    sync.handleChanges([{ id: 'entity:new', version: 1, patch: { fake: true } }]);
    await settle();
    expect(calls).toEqual([['entity:new', undefined]]);
    expect(store.get('entity:new')?.state).toEqual({ real: true });
  });

  it('GETs when the next version has no patch', async () => {
    const { store, sync, calls, settle } = setup({
      'entity:o': res('entity:o', 5, { n: 5 }),
    });
    store.put(res('entity:o', 4, { n: 4 }));
    sync.handleChanges([{ id: 'entity:o', version: 5 }]);
    await settle();
    expect(calls).toEqual([['entity:o', 4]]);
    expect(store.get('entity:o')?.state).toEqual({ n: 5 });
  });

  it('reports a failed GET', async () => {
    const { sync, onError, settle } = setup({});
    sync.handleChanges([{ id: 'entity:x', version: 1 }]);
    await settle();
    expect(onError).toHaveBeenCalledWith('404 entity:x');
  });
});

describe('AreaSync.handleDigest', () => {
  it('GETs higher or unknown ids, not equal ones', async () => {
    const { store, sync, calls, settle } = setup({
      'entity:o': res('entity:o', 7),
      'entity:new': res('entity:new', 1),
    });
    store.put(res('entity:o', 5));
    store.put(res('entity:p', 3));
    sync.handleDigest({ 'entity:o': 7, 'entity:p': 3, 'entity:new': 1 });
    await settle();
    expect(calls.map((c) => c[0]).sort()).toEqual(['entity:new', 'entity:o']);
  });

  it('a higher area version GETs the area, then its unknown children', async () => {
    const { store, sync, calls, settle } = setup({
      [AREA]: res(AREA, 4, {}, ['entity:1', 'entity:2']),
      'entity:2': res('entity:2', 1),
    });
    store.put(res(AREA, 3, {}, ['entity:1']));
    store.put(res('entity:1', 1));
    sync.handleDigest({ [AREA]: 4 });
    await settle();
    expect(calls).toEqual([
      [AREA, 3],
      ['entity:2', undefined],
    ]);
    expect(store.get('entity:2')).toBeDefined();
  });
});
