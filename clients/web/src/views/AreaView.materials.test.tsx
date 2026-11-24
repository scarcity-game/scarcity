import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const WALL = 'entity:00000000-0000-4000-8000-000000000003';
const enc = encodeURIComponent;
const GAME_PATH = '/games/00000000-0000-4000-8000-000000000001';
const AREA_PATH = `${GAME_PATH}/areas/00000000-0000-4000-8000-000000000002`;

const resource = (id: string, kind: string, fields: object, children: string[] = []) => ({
  ...{ id, resourceKind: kind, version: 1, children },
  ...fields,
});
const wall = resource(WALL, 'entity', {
  entityKind: 'fixed',
  name: 'wall',
  material: 'oak',
  position: { x: 1, y: 0.5 },
  polygon: [
    { x: 0, y: 0 },
    { x: 2, y: 0 },
    { x: 2, y: 1 },
    { x: 0, y: 1 },
  ],
});
const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

function mockWorld(gameFields: object) {
  const area = resource(AREA, 'area', { name: 'Test room', time: 0 }, [WALL]);
  const game = resource(GAME, 'game', { name: 'Test area', ...gameFields }, [AREA]);
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources/${enc(GAME)}`]: () => json(200, game),
    [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () => page([area]),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, area),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([wall]),
  });
}

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

describe('area view wall materials', () => {
  it("fills a wall with the pattern of its game's materialPatterns", async () => {
    mockWorld({ materialPatterns: { oak: 'timber' } });
    const { container } = renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
    await waitFor(() => expect(container.querySelector('pattern use')).not.toBeNull());
    expect(container.querySelector('pattern use')).toHaveAttribute('href', '#pattern.timber');
    const id = container.querySelector('pattern')?.id;
    expect(container.querySelector('.polygon-shape')).toHaveAttribute('fill', `url(#${id})`);
    expect(container.querySelector('symbol[id="pattern.timber"]')).not.toBeNull();
  });

  it('still draws the wall when the game has no materialPatterns', async () => {
    mockWorld({});
    const { container } = renderAt(AREA_PATH);
    await waitFor(() => expect(container.querySelector('.polygon-shape')).not.toBeNull());
  });
});
