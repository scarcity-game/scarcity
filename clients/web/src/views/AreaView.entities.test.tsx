import { screen, waitFor, within, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
const PEBBLE = 'entity:00000000-0000-4000-8000-000000000005';
const enc = encodeURIComponent;
const GAME_PATH = '/games/00000000-0000-4000-8000-000000000001';
const AREA_PATH = `${GAME_PATH}/areas/00000000-0000-4000-8000-000000000002`;

const areaRes = {
  id: AREA,
  resourceKind: 'area',
  version: 3,
  children: [PILLAR, ORBITER],
  name: 'Test room',
  time: 1042.0,
};

const pillar = {
  id: PILLAR,
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...{ entityKind: 'fixed', name: 'pillar', position: { x: 5, y: 4 } },
};

const orbiter = (version: number, x: number, y: number) => ({
  id: ORBITER,
  resourceKind: 'entity',
  version,
  children: [],
  ...{ entityKind: 'mob', name: 'orbiter', position: { x, y } },
});

const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

function mockWorld(extra: Parameters<typeof mockApi>[0] = {}) {
  return mockApi({
    ...extra,
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources?type=game`]: () =>
      page([
        {
          id: GAME,
          resourceKind: 'game',
          version: 2,
          children: [AREA],
          state: { name: 'Test area' },
        },
      ]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, {
        id: GAME,
        resourceKind: 'game',
        version: 2,
        children: [AREA],
        state: { name: 'Test area' },
      }),
    [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () => page([areaRes]),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, areaRes),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () =>
      page([pillar, orbiter(1, 8, 4)]),
  });
}

describe('area view entities appear and disappear', () => {
  it('a created entity appears after one GET of it', async () => {
    const api = mockWorld({
      [`GET /api/world/resources/${enc(PEBBLE)}`]: () =>
        json(200, { id: PEBBLE, resourceKind: 'entity', version: 1, children: [], name: 'pebble', position: { x: 2, y: 2 } }),
    });
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).not.toBeNull(),
    ); // children loaded
    const notLog = () => api.requests.filter((r) => !r.path.startsWith('/api/world/log'));
    const beforeLength = notLog().length;
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          { id: AREA, resourceKind: 'area', version: 4, childrenAdded: [PEBBLE] },
          { id: PEBBLE, resourceKind: 'entity', version: 1, created: true },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + PEBBLE + '"]')).not.toBeNull(),
    );
    expect(notLog()).toHaveLength(beforeLength + 1);
    expect(notLog().at(-1)?.path).toBe(`/api/world/resources/${enc(PEBBLE)}`);
  });

  it('an entity with a radius is drawn as a circle of that radius', async () => {
    mockWorld({
      [`GET /api/world/resources/${enc(PEBBLE)}`]: () =>
        json(200, {
          id: PEBBLE,
          resourceKind: 'entity',
          version: 1,
          children: [],
          name: 'pebble',
          position: { x: 2, y: 2 },
          radius: 0.5,
        }),
    });
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).not.toBeNull(),
    ); // children loaded
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          { id: AREA, resourceKind: 'area', version: 4, childrenAdded: [PEBBLE] },
          { id: PEBBLE, resourceKind: 'entity', version: 1, created: true },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + PEBBLE + '"] circle')).toHaveAttribute('r', '0.5'),
    );
  });

  it('a destroyed entity disappears', async () => {
    mockWorld();
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).not.toBeNull(),
    ); // children loaded
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          { id: AREA, resourceKind: 'area', version: 4, childrenRemoved: [ORBITER] },
          {
            id: ORBITER,
            resourceKind: 'entity',
            deleted: true,
          },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).toBeNull(),
    );
    const panel = within(screen.getByRole('complementary', { name: 'Inspector' }));
    expect(panel.queryByRole('button', { name: 'orbiter' })).not.toBeInTheDocument();
    expect(container.querySelector('[data-entity-id="' + PILLAR + '"]')).not.toBeNull();
  });

  it('a batch with the child deleted before the parent change also works', async () => {
    mockWorld();
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).not.toBeNull(),
    ); // children loaded
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          {
            id: ORBITER,
            resourceKind: 'entity',
            deleted: true,
          },
          { id: AREA, resourceKind: 'area', version: 4, childrenRemoved: [ORBITER] },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).toBeNull(),
    );
    const panel = within(screen.getByRole('complementary', { name: 'Inspector' }));
    expect(panel.queryByRole('button', { name: 'orbiter' })).not.toBeInTheDocument();
    expect(container.querySelector('[data-entity-id="' + PILLAR + '"]')).not.toBeNull();
  });
});
