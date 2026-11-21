import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const U = (n: number) => `00000000-0000-4000-8000-0000000000${n}`;
const GAME = `game:${U(21)}`;
const AREA = `area:${U(22)}`;
const enc = encodeURIComponent;
const GAME_PATH = `/games/${U(21)}`;

const area = {
  id: AREA,
  resourceKind: 'area',
  version: 1,
  children: [],
  name: 'Only room',
  width: 10,
  height: 8,
  time: 0,
};

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

describe('game page of a game with one area', () => {
  it('lists the area and stays, so "Back to game" does not bounce back', async () => {
    // No active entity: the list must not be the Active filter's empty one.
    mockApi({
      'GET /api/auth/me': () => json(200, ALICE),
      [`GET /api/world/resources/${enc(GAME)}`]: () =>
        json(200, { id: GAME, resourceKind: 'game', version: 1, children: [AREA], name: 'Solo' }),
      [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () =>
        json(200, { items: [area], page: 0, size: 200, total: 1 }),
      [`GET /api/world/resources?type=area&game=${enc(GAME)}&size=50&page=0`]: () =>
        json(200, { items: [area], page: 0, size: 50, total: 1 }),
    });
    renderAt(GAME_PATH);
    expect(await screen.findByRole('link', { name: 'Only room' })).toHaveAttribute(
      'href',
      `${GAME_PATH}/areas/${U(22)}`,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Solo' })).toBeInTheDocument();
    expect(screen.queryByText(/Looking for the area/)).toBeNull();
    expect(currentLocation()).toBe(GAME_PATH);
  });
});
