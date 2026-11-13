import { screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
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

const logEntry = (seq: number, extra: Record<string, unknown> = {}) => ({
  seq,
  area: AREA,
  time: 1042 + seq,
  kind: 'action-start',
  cause: null,
  root: seq,
  resource: ORBITER,
  action: { type: 'throw', seq },
  draws: [],
  changes: [],
  ...extra,
});
const logPage = (entries: unknown[]) =>
  json(200, {
    entries,
    first: entries.length ? 1 : null,
    last: entries.length ? (entries[entries.length - 1] as { seq: number }).seq : null,
  });
const LOG_PATH = (after: number) => `GET /api/world/log?area=${enc(AREA)}&after=${after}&limit=500`;

function mockWorld(extra: Record<string, (() => Response) | Array<() => Response>> = {}) {
  return mockApi({
    [LOG_PATH(0)]: () => logPage([]),
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
    ...extra,
    // The log store's first request is a one-entry probe for first and last.
    [`GET /api/world/log?area=${enc(AREA)}&after=0&limit=1`]: extra[LOG_PATH(0)] ?? (() => logPage([])),
  });
}

describe('area view log follows logSeq', () => {
  it('a changes message with logSeq reads the log once and shows the new entry', async () => {
    const api = mockWorld({
      [LOG_PATH(0)]: () => logPage([logEntry(1)]),
      // The read on going live finds nothing; the one after logSeq finds entry 2.
      [LOG_PATH(1)]: [
        () => logPage([]),
        () => logPage([logEntry(2, { root: 2, action: { type: 'wait', seq: 2 } })]),
      ],
    });
    renderAt(AREA_PATH);
    const logPanel = await screen.findByRole('region', { name: 'Log' });
    await waitFor(() => expect(logPanel.querySelector('[data-seq="1"]')).not.toBeNull());

    const logRequests = (after: number) =>
      api.requests.filter((r) => r.path === `/api/world/log?area=${enc(AREA)}&after=${after}&limit=500`).length;

    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    // Going live reads once.
    await waitFor(() => expect(logRequests(1)).toBe(1));

    // Wait 300 ms
    await new Promise((r) => setTimeout(r, 300));

    const before = logRequests(1);

    act(() => ws.receive({ type: 'changes', area: AREA, logSeq: 2, changes: [] }));

    await waitFor(() => expect(logPanel.querySelector('[data-seq="2"]')).not.toBeNull());
    expect(logRequests(1)).toBe(before + 1);
  });

  it('3 s of silence on a live socket after a logSeq causes no log request', async () => {
    const api = mockWorld({
      [LOG_PATH(0)]: () => logPage([logEntry(1)]),
      [LOG_PATH(1)]: () => logPage([]),
    });

    renderAt(AREA_PATH);
    const logPanel = await screen.findByRole('region', { name: 'Log' });
    await waitFor(() => expect(logPanel.querySelector('[data-seq="1"]')).not.toBeNull());

    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));

    // Wait 300 ms
    await new Promise((r) => setTimeout(r, 300));

    // Receive digest with logSeq = 1
    act(() => ws.receive({ type: 'digest', area: AREA, versions: {}, logSeq: 1 }));

    const count = api.requests.filter((r) => r.path.startsWith('/api/world/log')).length;

    // Wait 3000 ms (3 seconds)
    await new Promise((r) => setTimeout(r, 3000));

    expect(api.requests.filter((r) => r.path.startsWith('/api/world/log')).length).toBe(count);
  }, 10000);
});
