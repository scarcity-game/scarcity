import { act, fireEvent, screen, waitFor } from '@testing-library/react';
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
const pathOf = (areaUuid: string) => `/games/${U(11)}/areas/${areaUuid}`;

const entity = (id: string, entityKind: string) => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind,
  name: id === DIVER ? 'diver' : 'mouse',
  position: { x: 1, y: 1 },
});
const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });
const logPage = () => json(200, { entries: [], first: null, last: null });

/** Where each entity is now: area id, or null while it is between areas. */
type Where = Record<string, string | null>;

function mockWorld(where: Where, onAreasListed: () => void = () => {}) {
  const held = (areaId: string) => Object.keys(where).filter((id) => where[id] === areaId);
  const area = (id: string, name: string) => ({
    ...{ id, resourceKind: 'area', version: 1, name, width: 10, height: 8, time: 0 },
    children: held(id),
  });
  const routes: Record<string, () => Response> = {
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, { id: GAME, resourceKind: 'game', version: 1, children: [AREA_A, AREA_B] }),
    [`GET /api/world/resources/${enc(GAME)}/children?size=200&page=0`]: () => {
      onAreasListed();
      return page([area(AREA_A, 'Depth 0'), area(AREA_B, 'Depth 1')]);
    },
  };
  for (const [id, name] of [
    [AREA_A, 'Depth 0'],
    [AREA_B, 'Depth 1'],
  ] as const) {
    routes[`GET /api/world/resources/${enc(id)}`] = () => json(200, area(id, name));
    routes[`GET /api/world/resources/${enc(id)}/children?size=200&page=0`] = () =>
      page(held(id).map((e) => entity(e, e === DIVER ? 'npc' : 'mob')));
    routes[`GET /api/world/log?area=${enc(id)}&after=0&limit=1`] = logPage;
    routes[`GET /api/world/log?area=${enc(id)}&after=0&limit=500`] = logPage;
  }
  return mockApi(routes);
}

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

const markOf = (id: string) => document.querySelector(`[data-entity-id="${id}"]`) as Element;

async function openDepthZero() {
  renderAt(pathOf(U(12)));
  const heading = await screen.findByRole(
    'heading',
    { level: 1, name: 'Depth 0' },
    { timeout: 4000 },
  );
  expect(heading).toBeInTheDocument();
  await waitFor(() => expect(markOf(DIVER)).not.toBeNull());
  act(() => StubWebSocket.last.open());
  act(() => StubWebSocket.last.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
}

/** The diver is gone from depth 0 (its list and a deleted change). */
function diverLeavesDepthZero() {
  act(() =>
    StubWebSocket.last.receive({
      type: 'changes',
      area: AREA_A,
      changes: [
        { id: AREA_A, resourceKind: 'area', version: 2, childrenRemoved: [DIVER] },
        { id: DIVER, resourceKind: 'entity', deleted: true },
      ],
    }),
  );
}

function followDiver() {
  fireEvent.click(markOf(DIVER));
  fireEvent.click(screen.getByRole('button', { name: 'Follow' }));
}

describe('a spectator who is not following', () => {
  it('stays on the area when an entity leaves it', async () => {
    const where: Where = { [DIVER]: AREA_A, [MOUSE]: AREA_A };
    mockWorld(where);
    await openDepthZero();
    where[DIVER] = AREA_B;
    diverLeavesDepthZero();
    await waitFor(() => expect(markOf(DIVER)).toBeNull());
    expect(screen.getByRole('heading', { level: 1, name: 'Depth 0' })).toBeInTheDocument();
    expect(currentLocation()).toBe(pathOf(U(12)));
  });

  it('stays when only selecting, not following, the entity that leaves', async () => {
    const where: Where = { [DIVER]: AREA_A, [MOUSE]: AREA_A };
    mockWorld(where);
    await openDepthZero();
    fireEvent.click(markOf(DIVER));
    where[DIVER] = AREA_B;
    diverLeavesDepthZero();
    await waitFor(() => expect(markOf(DIVER)).toBeNull());
    expect(currentLocation()).toBe(pathOf(U(12)));
  });
});

describe('a spectator who follows an entity', () => {
  it('offers Follow, then Stop following', async () => {
    mockWorld({ [DIVER]: AREA_A, [MOUSE]: AREA_A });
    await openDepthZero();
    followDiver();
    const stop = screen.getByRole('button', { name: 'Stop following' });
    expect(stop).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(stop);
    expect(screen.getByRole('button', { name: 'Follow' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('changes area with the entity and goes on following it there', async () => {
    const where: Where = { [DIVER]: AREA_A, [MOUSE]: AREA_A };
    mockWorld(where);
    await openDepthZero();
    followDiver();
    where[DIVER] = AREA_B;
    diverLeavesDepthZero();
    expect(await screen.findByRole('heading', { level: 1, name: 'Depth 1' })).toBeInTheDocument();
    expect(currentLocation()).toBe(pathOf(U(13)));
    await waitFor(() => expect(markOf(DIVER)).not.toBeNull());
    fireEvent.click(markOf(DIVER));
    expect(screen.getByRole('button', { name: 'Stop following' })).toBeInTheDocument();
  });

  it('looks again while the entity is between areas', async () => {
    const where: Where = { [DIVER]: AREA_A, [MOUSE]: AREA_A };
    let listed = 0;
    mockWorld(where, () => {
      listed += 1;
      if (listed === 2) where[DIVER] = AREA_B; // it arrives by the second look
    });
    await openDepthZero();
    followDiver();
    where[DIVER] = null;
    diverLeavesDepthZero();
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Depth 1' }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(listed).toBeGreaterThanOrEqual(2);
  });

  it('stays after it stops following, when the entity leaves', async () => {
    const where: Where = { [DIVER]: AREA_A, [MOUSE]: AREA_A };
    mockWorld(where);
    await openDepthZero();
    followDiver();
    fireEvent.click(screen.getByRole('button', { name: 'Stop following' }));
    where[DIVER] = AREA_B;
    diverLeavesDepthZero();
    await waitFor(() => expect(markOf(DIVER)).toBeNull());
    expect(currentLocation()).toBe(pathOf(U(12)));
  });

  it('centres the map on the followed entity', async () => {
    mockWorld({ [DIVER]: AREA_A, [MOUSE]: AREA_A });
    await openDepthZero();
    const viewBox = () => document.querySelector('.room-svg')?.getAttribute('viewBox');
    expect(viewBox()).toBe('-0.5 -0.5 11 9');
    followDiver();
    expect(viewBox()).not.toBe('-0.5 -0.5 11 9');
  });
});
