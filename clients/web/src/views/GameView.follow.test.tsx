import { screen } from '@testing-library/react';
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
type Placed = { id: string; entityKind?: string };

/**
 * Two areas holding `inA` and `inB`. `laterA` is what depth 0 holds when the game
 * page looks again (after the area view was opened with `inA`).
 */
function mockWorld(inA: Placed[], inB: Placed[], laterA = inA) {
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
    [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () =>
      page([areaViewOpened ? area(AREA_A, 'Depth 0', idsOf(laterA)) : a, b]),
  };
  // The game-scoped list: one request per kind, not one per area (the entities a kind has,
  // in the areas as the game page sees them).
  const inGame = (kind: string) =>
    [...(areaViewOpened ? laterA : inA), ...inB].filter((e) => e.entityKind === kind);
  for (const kind of ['player', 'npc']) {
    routes[`GET /api/world/resources?type=entity&game=${enc(GAME)}&entityKind=${kind}&size=1`] =
      () => page(inGame(kind).slice(0, 1));
  }
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
    const heading = await screen.findByRole(
      'heading',
      { level: 1, name: 'Depth 1' },
      { timeout: 4000 },
    );
    expect(heading).toBeInTheDocument();
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
    expect(screen.getByRole('link', { name: 'All areas of this game' })).toHaveAttribute(
      'href',
      `${GAME_PATH}/areas`,
    );
  });
});
