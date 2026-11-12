import { screen, waitFor, within, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
const enc = encodeURIComponent;

const areaRes = {
  id: AREA,
  type: 'area',
  version: 3,
  children: [PILLAR, ORBITER],
  state: { name: 'Test room', width: 10, height: 8 },
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

describe('area view', () => {
  it('loads the area, shows it, subscribes after ready and moves the orbiter on changes', async () => {
    const api = mockWorld();
    const { container } = renderAt(`/games/${enc(GAME)}/areas/${enc(AREA)}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Test room' })).toBeInTheDocument();
    const orbiterMark = () => container.querySelector(`[data-entity-id="${ORBITER}"] circle`);
    await waitFor(() => expect(orbiterMark()).toHaveAttribute('cx', '8'));
    expect(orbiterMark()).toHaveAttribute('cy', '4');
    for (const r of api.requests.filter((r) => r.path.startsWith('/api/world'))) {
      expect(r.headers.get('Authorization')).toBe('Bearer jwt-alice');
    }

    // Socket: auth first, subscribe only after ready.
    const ws = StubWebSocket.last;
    expect(ws.url).toMatch(/^ws:\/\/.*\/api\/subscriptions\/ws$/);
    act(() => ws.open());
    expect(ws.sent).toEqual([{ type: 'auth', token: 'jwt-alice' }]);
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
    expect(ws.sent.at(-1)).toEqual({ type: 'subscribe', area: AREA });
    expect(screen.getByRole('status')).toHaveTextContent('Live');

    // Digest equal to what we have: no extra GETs.
    const before = api.requests.length;
    act(() =>
      ws.receive({
        type: 'digest',
        area: AREA,
        versions: { [AREA]: 3, [PILLAR]: 1, [ORBITER]: 1 },
      }),
    );
    expect(api.requests).toHaveLength(before);

    // A tick: the orbiter moves to (5, 7), which is drawn at y = 8 - 7 = 1.
    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          {
            id: ORBITER,
            type: 'entity',
            version: 2,
            patch: orbiter(2, 5, 7).state,
          },
        ],
      }),
    );
    expect(orbiterMark()).toHaveAttribute('cx', '5');
    expect(orbiterMark()).toHaveAttribute('cy', '1');
    const panel = within(screen.getByRole('complementary', { name: 'Resources' }));
    const item = panel.getByText(ORBITER).closest('li') as HTMLElement;
    expect(within(item).getByText('2')).toHaveClass('resource-version');
    expect(item).toHaveTextContent('"x": 5');
    expect(api.requests).toHaveLength(before); // patch applied without a GET
  });

  it('leaving the page closes the socket', async () => {
    mockWorld();
    renderAt(`/games/${enc(GAME)}/areas/${enc(AREA)}`);
    await screen.findByRole('heading', { level: 1, name: 'Test room' });
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
    act(() => screen.getByRole('button', { name: 'Log out' }).click());
    await waitFor(() => expect(ws.closed).toBe(true));
  });

  it('a 4401 close sends the player back to login', async () => {
    mockWorld();
    renderAt(`/games/${enc(GAME)}/areas/${enc(AREA)}`);
    await screen.findByRole('heading', { level: 1, name: 'Test room' });
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.serverClose(4401));
    await waitFor(() => expect(currentLocation()).toMatch(/^\/login/));
    expect(StubWebSocket.instances).toHaveLength(1);
  });
});

describe('games and game views', () => {
  it('lists games and opens one, which auto-opens its only area', async () => {
    mockWorld();
    renderAt('/games');
    const link = await screen.findByRole('link', { name: 'Test area' });
    expect(link).toHaveAttribute('href', `/games/${enc(GAME)}`);
    expect(screen.getByText(GAME)).toBeInTheDocument();
    act(() => link.click());
    expect(await screen.findByRole('heading', { level: 1, name: 'Test room' })).toBeInTheDocument();
    expect(currentLocation()).toBe(`/games/${enc(GAME)}/areas/${enc(AREA)}`);
  });
});
