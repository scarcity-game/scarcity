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
  resourceType: 'area',
  version: 3,
  children: [PILLAR, ORBITER],
  state: {
    name: 'Test room',
    width: 10,
    height: 8,
    time: 1042.0,
  },
};
const pillar = {
  id: PILLAR,
  resourceType: 'entity',
  version: 1,
  children: [],
  state: { entityKind: 'fixed', name: 'pillar', position: { x: 5, y: 4 } },
};
const orbiter = (version: number, x: number, y: number) => ({
  id: ORBITER,
  resourceType: 'entity',
  version,
  children: [],
  state: { entityKind: 'mobile', name: 'orbiter', position: { x, y } },
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
  actionInProgress: { type: 'throw', seq },
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
          resourceType: 'game',
          version: 2,
          children: [AREA],
          state: { name: 'Test area' },
        },
      ]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, {
        id: GAME,
        resourceType: 'game',
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
    [`GET /api/world/log?area=${enc(AREA)}&after=0&limit=1`]:
      extra[LOG_PATH(0)] ?? (() => logPage([])),
  });
}

const history = logEntry(1, { kind: 'action-end', actionInProgress: { type: 'wait', seq: 1 } });
const newEntries = [
  logEntry(2, {
    kind: 'intent',
    time: 1042,
    controller: { kind: 'service', principal: 'orbital' },
    actionSpec: { type: 'step', params: { to: { x: 4, y: 4 } } },
  }),
  logEntry(3, {
    kind: 'action-start',
    time: 1042,
    cause: 2,
    root: 2,
    actionInProgress: { type: 'step', seq: 3 },
  }),
  logEntry(4, {
    kind: 'action-end',
    time: 1043,
    cause: 3,
    root: 2,
    actionInProgress: { type: 'step', params: { to: { x: 4, y: 4 } }, seq: 3 },
    result: { to: { x: 4, y: 4 }, reached: true, stoppedAt: { x: 4, y: 4 } },
    changes: [
      { id: ORBITER, type: 'entity', op: 'changed', version: 2, state: orbiter(2, 4, 4).state },
    ],
  }),
];

describe('area view animates an advance', () => {
  it('shows the new game time at once, and walks the orbiter to its end state over the beats', async () => {
    mockWorld({
      [LOG_PATH(0)]: () => logPage([history]),
      [LOG_PATH(1)]: [() => logPage([]), () => logPage(newEntries)],
    });
    const { container } = renderAt(AREA_PATH);
    const mark = () => container.querySelector(`[data-entity-id="${ORBITER}"] circle`);
    await waitFor(() => expect(mark()).toHaveAttribute('cx', '8'));
    const ws = StubWebSocket.last;
    act(() => ws.open());
    act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
    await new Promise((r) => setTimeout(r, 100));

    // Everything the room draws for the orbiter from now on.
    const seen: number[] = [];
    const observer = new MutationObserver(() => {
      const cx = mark()?.getAttribute('cx');
      if (cx != null) seen.push(Number(cx));
    });
    observer.observe(container, { attributes: true, subtree: true, childList: true });

    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        logSeq: 4,
        changes: [
          { id: AREA, resourceType: 'area', version: 4, patch: { ...areaRes.state, time: 1043.0 } },
          { id: ORBITER, resourceType: 'entity', version: 2, patch: orbiter(2, 4, 4).state },
        ],
      }),
    );
    // The readout jumps with the update; it does not step through the entries' times.
    expect(screen.getByRole('timer')).toHaveTextContent('game time 1043.00');

    // The orbiter ends at its end state ...
    await waitFor(() => expect(mark()).toHaveAttribute('cx', '4'), { timeout: 2000 });
    await new Promise((r) => setTimeout(r, 100));
    observer.disconnect();
    expect(mark()).toHaveAttribute('cx', '4');
    // ... after having been drawn on the way, never past either end.
    expect(seen.some((cx) => cx > 4 && cx < 8)).toBe(true);
    expect(Math.min(...seen)).toBeGreaterThanOrEqual(4);
    expect(Math.max(...seen)).toBeLessThanOrEqual(8);
    // The panel and the readout show the truth throughout.
    expect(screen.getByRole('timer')).toHaveTextContent('game time 1043.00');
    // The log panel shows the intent line.
    const intentLine = container.querySelector('[data-seq="2"] .log-what');
    expect(intentLine).toHaveTextContent('orbital chose step');
  });

  it('does not animate the history read when the view opens', async () => {
    mockWorld({
      [LOG_PATH(0)]: () => logPage([history, ...newEntries]),
      [LOG_PATH(4)]: () => logPage([]),
    });
    const { container } = renderAt(AREA_PATH);
    const mark = () => container.querySelector(`[data-entity-id="${ORBITER}"] circle`);
    await waitFor(() => expect(container.querySelector('[data-seq="4"]')).not.toBeNull());
    expect(mark()).toHaveAttribute('cx', '8');
    expect(container.querySelector('[data-acting="true"]')).toBeNull();
  });
});
