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
  type: 'area',
  version: 3,
  children: [PILLAR, ORBITER],
  state: {
    name: 'Test room',
    width: 10,
    height: 8,
    clock: { gameTime: 1042.0, wallTime: 1794441600000, rate: 0 },
  },
};

const pillar = {
  id: PILLAR,
  type: 'entity',
  version: 1,
  children: [],
  state: { kind: 'fixed', name: 'pillar', position: { x: 5, y: 4 } },
};

const orbiter = (version: number, x: number, y: number) => ({
  id: ORBITER,
  type: 'entity',
  version,
  children: [],
  state: { kind: 'mobile', name: 'orbiter', position: { x, y } },
});

const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

function mockWorld() {
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources?type=game`]: () =>
      page([
        {
          id: GAME,
          type: 'game',
          version: 2,
          children: [AREA],
          state: { name: 'Test area' },
        },
      ]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, {
        id: GAME,
        type: 'game',
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
  it('a created entity with a patch appears without a GET', async () => {
    const api = mockWorld();
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).not.toBeNull(),
    ); // children loaded
    const beforeLength = api.requests.length;
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          {
            id: AREA,
            type: 'area',
            version: 4,
            patch: areaRes.state,
            children: [PILLAR, ORBITER, PEBBLE],
          },
          {
            id: PEBBLE,
            type: 'entity',
            version: 1,
            patch: { name: 'pebble', position: { x: 2, y: 2 } },
          },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + PEBBLE + '"]')).not.toBeNull(),
    );
    expect(api.requests).toHaveLength(beforeLength);
  });

  it('an entity with a radius is drawn as a circle of that radius', async () => {
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
            id: AREA,
            type: 'area',
            version: 4,
            patch: areaRes.state,
            children: [PILLAR, ORBITER, PEBBLE],
          },
          {
            id: PEBBLE,
            type: 'entity',
            version: 1,
            patch: { name: 'pebble', position: { x: 2, y: 2 }, radius: 0.5 },
          },
        ],
      }),
    );

    const pebbleElement = container.querySelector('[data-entity-id="' + PEBBLE + '"] circle');
    expect(pebbleElement).toHaveAttribute('r', '0.5');
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
          {
            id: AREA,
            type: 'area',
            version: 4,
            patch: areaRes.state,
            children: [PILLAR],
          },
          {
            id: ORBITER,
            type: 'entity',
            deleted: true,
          },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).toBeNull(),
    );
    const panel = within(screen.getByRole('complementary', { name: 'Resources' }));
    expect(panel.queryByText(ORBITER)).not.toBeInTheDocument();
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
            type: 'entity',
            deleted: true,
          },
          {
            id: AREA,
            type: 'area',
            version: 4,
            patch: areaRes.state,
            children: [PILLAR],
          },
        ],
      }),
    );

    await waitFor(() =>
      expect(container.querySelector('[data-entity-id="' + ORBITER + '"]')).toBeNull(),
    );
    const panel = within(screen.getByRole('complementary', { name: 'Resources' }));
    expect(panel.queryByText(ORBITER)).not.toBeInTheDocument();
    expect(container.querySelector('[data-entity-id="' + PILLAR + '"]')).not.toBeNull();
  });
});
