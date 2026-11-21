import { describe, expect, it, vi } from 'vitest';
import { ResourceStore, type Resource } from './cache.ts';
import { applyUpdate, AreaSync, type Change } from './sync.ts';
import { checksumHex, versionChecksum } from './versionChecksum.ts';

const res = (
  id: string,
  version: number,
  state: Record<string, unknown> = {},
  children: string[] = [],
): Resource => ({ id, resourceKind: id.split(':')[0] ?? '', version, children, ...state });

const AREA = 'area:00000000-0000-4000-8000-0000000000a1';
const E1 = 'entity:00000000-0000-4000-8000-0000000000e1';
const E2 = 'entity:00000000-0000-4000-8000-0000000000e2';
const E3 = 'entity:00000000-0000-4000-8000-0000000000e3';

const update = (id: string, version: number, delta: Partial<Change> = {}): Change => ({
  resourceKind: id.split(':')[0],
  id,
  version,
  ...delta,
});

/** A world: what GET, children and versions answer. `'same'` answers 304. */
function setup(server: Record<string, Resource | 'same'>, logSeq?: number) {
  const store = new ResourceStore();
  const gets: [string, number | undefined][] = [];
  const get = vi.fn(async (id: string, v?: number) => {
    gets.push([id, v]);
    const r = server[id];
    if (!r) throw new Error(`404 ${id}`);
    return r === 'same' ? undefined : r;
  });
  const children = vi.fn(async (id: string) => {
    const area = server[id];
    return area && area !== 'same'
      ? area.children.map((c) => server[c]).filter((r): r is Resource => !!r && r !== 'same')
      : [];
  });
  const versions = vi.fn(async (id: string) => {
    const out: Record<string, number> = {};
    for (const r of Object.values(server)) if (r !== 'same') out[r.id] = r.version;
    return { id, versions: out, checksum: checksumHex(versionChecksum(Object.entries(out))), logSeq };
  });
  const onError = vi.fn();
  const sync = new AreaSync(AREA, store, { get, children, versions }, onError);
  const settle = () => new Promise((r) => setTimeout(r, 0));
  return { store, sync, gets, get, versions, onError, settle };
}

const checksumOf = (rs: Resource[]) => checksumHex(versionChecksum(rs.map((r) => [r.id, r.version])));

describe('applyUpdate', () => {
  it('sets, unsets and moves children, keeping the rest', () => {
    const before = res(E1, 4, { hp: 3, name: 'm', controller: { kind: 'service' } }, ['item:a', 'item:b']);
    const after = applyUpdate(
      before,
      update(E1, 5, { set: { hp: 2 }, unset: ['controller'], childrenAdded: ['item:c'], childrenRemoved: ['item:a'] }),
      5,
    );
    expect(after).toEqual(res(E1, 5, { hp: 2, name: 'm' }, ['item:b', 'item:c']));
  });
});

describe('AreaSync: loading', () => {
  it('loads the area and every page of its children', async () => {
    const area = res(AREA, 3, {}, [E1, E2]);
    const { store, sync } = setup({ [AREA]: area, [E1]: res(E1, 1), [E2]: res(E2, 2) });
    await sync.load();
    expect(store.ids().sort()).toEqual([AREA, E1, E2].sort());
  });
});

describe('AreaSync: changes', () => {
  async function loaded(extra: Record<string, Resource | 'same'> = {}) {
    const s = setup({ [AREA]: res(AREA, 3, {}, [E1]), [E1]: res(E1, 4, { x: 1 }), ...extra });
    await s.sync.load();
    s.gets.length = 0;
    return s;
  }

  it('applies an update at cached + 1 without a GET', async () => {
    const { store, sync, gets, settle } = await loaded();
    sync.handleChanges({ changes: [update(E1, 5, { set: { x: 2 } })] });
    await settle();
    expect(store.get(E1)).toEqual(res(E1, 5, { x: 2 }));
    expect(gets).toEqual([]);
  });

  it('ignores a duplicate or older update', async () => {
    const { store, sync, gets, settle } = await loaded();
    sync.handleChanges({ changes: [update(E1, 4, { set: { x: 9 } }), update(E1, 3, { set: { x: 9 } })] });
    await settle();
    expect(store.get(E1)?.x).toBe(1);
    expect(gets).toEqual([]);
  });

  it('GETs on a version gap, with the cached version', async () => {
    const { sync, gets, settle } = await loaded();
    sync.handleChanges({ changes: [update(E1, 7, { set: { x: 7 } })] });
    await settle();
    expect(gets).toEqual([[E1, 4]]);
  });

  it('GETs a stub, and an update of a resource it does not hold', async () => {
    const { sync, gets, settle } = await loaded({ [E2]: res(E2, 2) });
    sync.handleChanges({ changes: [update(E1, 5), update(E2, 2, { set: {} })] });
    await settle();
    expect(gets.map(([id]) => id).sort()).toEqual([E1, E2].sort());
  });

  it('adds a created child to the area and GETs it', async () => {
    const { store, sync, gets, settle } = await loaded({ [E2]: res(E2, 1, { x: 5 }) });
    sync.handleChanges({
      changes: [update(AREA, 4, { set: { time: 2 }, childrenAdded: [E2] }), { resourceKind: 'entity', id: E2, version: 1, created: true }],
    });
    await settle();
    expect(store.get(AREA)?.children).toEqual([E1, E2]);
    expect(store.get(E2)).toEqual(res(E2, 1, { x: 5 }));
    expect(gets).toEqual([[E2, undefined]]);
  });

  it('drops a deleted resource and what is under it', async () => {
    const { store, sync, settle } = await loaded();
    store.put(res(E1, 4, { x: 1 }, ['item:i']));
    store.put(res('item:i', 1));
    sync.handleChanges({
      changes: [update(AREA, 4, { childrenRemoved: [E1] }), { resourceKind: 'entity', id: E1, deleted: true }],
    });
    await settle();
    expect(store.get(E1)).toBeUndefined();
    expect(store.get('item:i')).toBeUndefined();
    expect(store.get(AREA)?.children).toEqual([]);
  });

  it('notifies subscribers once per batch', async () => {
    const { store, sync, settle } = await loaded();
    const listener = vi.fn();
    store.subscribe(listener);
    sync.handleChanges({ changes: [update(AREA, 4, { set: { time: 2 } }), update(E1, 5, { set: { x: 2 } })] });
    await settle();
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe('AreaSync: the logSeq chain', () => {
  async function loadedAt(logSeq: number) {
    const s = setup({ [AREA]: res(AREA, 3, {}, [E1]), [E1]: res(E1, 4) }, logSeq);
    await s.sync.load();
    s.sync.handleChecksum(s.sync.checksum(), logSeq);
    return s;
  }

  it('follows consecutive batches without a resync', async () => {
    const { sync, versions, settle } = await loadedAt(10);
    sync.handleChanges({ changes: [update(E1, 5, { set: {} })], fromLogSeq: 10, logSeq: 12 });
    sync.handleChanges({ changes: [update(E1, 6, { set: {} })], fromLogSeq: 12, logSeq: 13 });
    await settle();
    expect(sync.logSeq).toBe(13);
    expect(versions).not.toHaveBeenCalled();
  });

  it('resyncs when a batch starts past the head it holds', async () => {
    const { sync, versions, settle } = await loadedAt(10);
    sync.handleChanges({ changes: [update(E1, 6, { set: {} })], fromLogSeq: 11, logSeq: 12 });
    await settle();
    expect(versions).toHaveBeenCalledTimes(1);
  });

  it('moves on after the last part of a split batch, whose parts repeat fromLogSeq', async () => {
    const { sync, versions, settle } = await loadedAt(10);
    sync.handleChanges({ changes: [update(E1, 5, { set: {} })], fromLogSeq: 10, logSeq: 14, part: 1, parts: 2 });
    expect(sync.logSeq).toBe(10);
    sync.handleChanges({ changes: [update(AREA, 4, { set: {} })], fromLogSeq: 10, logSeq: 14, part: 2, parts: 2 });
    await settle();
    expect(sync.logSeq).toBe(14);
    expect(versions).not.toHaveBeenCalled();
  });
});

describe('AreaSync: checksum and resync', () => {
  it('a matching checksum changes nothing; one that came before the load is checked after it', async () => {
    const area = res(AREA, 3, {}, [E1]);
    const e1 = res(E1, 4);
    const { sync, versions, settle } = setup({ [AREA]: area, [E1]: e1 });
    sync.handleChecksum(checksumOf([area, e1]), 5);
    await sync.load();
    await settle();
    expect(versions).not.toHaveBeenCalled();
    expect(sync.logSeq).toBe(5);
  });

  it('a mismatch resyncs: GETs what differs or is missing, drops what world does not list', async () => {
    const server: Record<string, Resource> = {
      [AREA]: res(AREA, 5, {}, [E1, E2]),
      [E1]: res(E1, 4),
      [E2]: res(E2, 1),
    };
    const { store, sync, gets, settle } = setup(server, 40);
    await sync.load();
    gets.length = 0;
    // Batches this client never saw: E2 went, E3 came, E1 moved on.
    server[AREA] = res(AREA, 6, {}, [E1, E3]);
    server[E1] = res(E1, 6, { x: 6 });
    server[E3] = res(E3, 1);
    delete server[E2];

    sync.handleChecksum('ffffffff', 40);
    for (let i = 0; i < 4; i++) await settle();

    expect(store.get(E2)).toBeUndefined();
    expect([...new Set(gets.map(([id]) => id))].sort()).toEqual([AREA, E1, E3].sort());
    expect(store.get(E1)?.version).toBe(6);
    expect(store.get(AREA)?.children).toEqual([E1, E3]);
    expect(sync.logSeq).toBe(40);
  });

  it('skips the comparison while a GET is in flight', async () => {
    const { sync, versions, settle } = setup({ [AREA]: res(AREA, 3, {}, []) });
    await sync.load();
    void sync.refresh(AREA);
    sync.handleChecksum('ffffffff');
    await settle();
    expect(versions).not.toHaveBeenCalled();
  });
});

describe('AreaSync: reload', () => {
  it('drops the area and loads it afresh, taking the reload logSeq', async () => {
    const server: Record<string, Resource> = { [AREA]: res(AREA, 3, {}, [E1]), [E1]: res(E1, 4) };
    const { store, sync } = setup(server);
    await sync.load();
    server[AREA] = res(AREA, 9, {}, [E2]);
    server[E2] = res(E2, 1);

    await sync.handleReload(30);

    expect(store.get(E1)).toBeUndefined();
    expect(store.get(AREA)?.version).toBe(9);
    expect(store.get(E2)?.version).toBe(1);
    expect(sync.logSeq).toBe(30);
  });
});
