import { act, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const U = (n: number) => `00000000-0000-4000-8000-0000000000${n}`;
const GAME = `game:${U(11)}`;
const AREA_A = `area:${U(12)}`;
const AREA_B = `area:${U(13)}`;
const DIVER = `entity:${U(15)}`;
const MOUSE = `entity:${U(16)}`;
const enc = encodeURIComponent;
const GAME_PATH = `/games/${U(11)}`;
const pathOf = (areaUuid: string) => `${GAME_PATH}/areas/${areaUuid}`;

const area = (id: string, name: string, children: string[]) => ({
  id,
  resourceKind: 'area',
  version: 1,
  children,
  name,
  width: 10,
  height: 8,
  time: 0,
});
const entity = (id: string, entityKind: string) => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind,
  name: id,
  position: { x: 1, y: 1 },
});
const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });
const logPage = () => json(200, { entries: [], first: null, last: null });
const idsOf = (items: { id: string }[]) => items.map((e) => e.id);

/**
 * Two areas holding `inA` and `inB`. `laterA` is what depth 0 holds when the game
 * page looks again (after the area view was opened with `inA`).
 */
function mockWorld(inA: { id: string }[], inB: { id: string }[], laterA = inA) {
  const a = area(AREA_A, 'Depth 0', idsOf(inA));
  const b = area(AREA_B, 'Depth 1', idsOf(inB));
  let areaViewOpened = false;
  const routes: Record<string, () => Response> = {
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, {
        id: GAME,
        resourceKind: 'game',
        version: 1,
        children: [AREA_A, AREA_B],
        name: 'Dive',
      }),
    [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () => page([a, b]),
    [`GET /api/world/resources/${enc(AREA_A)}/children?type=entity`]: () => page(laterA),
    [`GET /api/world/resources/${enc(AREA_B)}/children?type=entity`]: () => page(inB),
  };
  for (const [one, items] of [
    [a, inA],
    [b, inB],
  ] as const) {
    routes[`GET /api/world/resources/${enc(one.id)}`] = () => json(200, one);
    routes[`GET /api/world/resources/${enc(one.id)}/children?size=200&page=0`] = () => {
      areaViewOpened = true;
      return page(items);
    };
    routes[`GET /api/world/log?area=${enc(one.id)}&after=0&limit=1`] = logPage;
    routes[`GET /api/world/log?area=${enc(one.id)}&after=0&limit=500`] = logPage;
  }
  return { ...mockApi(routes), opened: () => areaViewOpened };
}

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

describe('game page follows the player or npc', () => {
  it('goes to the area holding the npc', async () => {
    mockWorld([entity(MOUSE, 'mob')], [entity(DIVER, 'npc')]);
    renderAt(GAME_PATH);
    expect(await screen.findByRole('heading', { level: 1, name: 'Depth 1' })).toBeInTheDocument();
    expect(currentLocation()).toBe(pathOf(U(13)));
  });

  it('prefers a player to an npc', async () => {
    mockWorld([entity(DIVER, 'npc')], [entity(MOUSE, 'player')]);
    renderAt(GAME_PATH);
    expect(await screen.findByRole('heading', { level: 1, name: 'Depth 1' })).toBeInTheDocument();
  });

  it('lists the areas while nobody is found', async () => {
    mockWorld([entity(MOUSE, 'mob')], []);
    renderAt(GAME_PATH);
    expect(
      await screen.findByText(/Looking for the area where the player or npc is/),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Depth 0' })).toHaveAttribute('href', pathOf(U(12)));
    expect(currentLocation()).toBe(GAME_PATH);
  });

  it('the area view goes back to the game page when the followed entity leaves', async () => {
    // Depth 0 holds the diver when opened; after it leaves, depth 1 holds it.
    mockWorld(
      [entity(DIVER, 'npc'), entity(MOUSE, 'mob')],
      [entity(DIVER, 'npc')],
      [entity(MOUSE, 'mob')],
    );
    renderAt(pathOf(U(12)));
    expect(await screen.findByRole('heading', { level: 1, name: 'Depth 0' })).toBeInTheDocument();
    await waitFor(() =>
      expect(document.querySelector(`[data-entity-id="${DIVER}"]`)).not.toBeNull(),
    );
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA_A,
        changes: [
          { ...area(AREA_A, 'Depth 0', [MOUSE]), version: 2 },
          { id: DIVER, resourceKind: 'entity', deleted: true },
        ],
      }),
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'Depth 1' })).toBeInTheDocument();
    expect(currentLocation()).toBe(pathOf(U(13)));
  });
});
