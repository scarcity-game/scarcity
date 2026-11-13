import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AREA, PEBBLE, PILLAR, throwEntries, throwSnapshot } from '../replay/throwFixture.ts';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
const enc = encodeURIComponent;
const AREA_PATH = '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000002';

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
  state: { kind: 'fixed', name: 'pillar', position: { x: 5, y: 4 }, hp: 100 },
};
const orbiter = {
  id: ORBITER,
  type: 'entity',
  version: 1,
  children: [],
  state: { kind: 'mobile', name: 'orbiter', position: { x: 8, y: 4 } },
};
const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });
const logPage = (entries: unknown[], first: number | null, last: number | null) =>
  json(200, { entries, first, last });

const LOG = `GET /api/world/log?area=${enc(AREA)}`;

function mockWorld(snapshots: unknown[] = [throwSnapshot]) {
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    'GET /api/world/resources?type=game': () =>
      page([{ id: GAME, type: 'game', version: 2, children: [AREA], state: { name: 'Test area' } }]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, { id: GAME, type: 'game', version: 2, children: [AREA], state: { name: 'Test area' } }),
    [`GET /api/world/resources/${enc(GAME)}/children?type=area`]: () => page([areaRes]),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, areaRes),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([pillar, orbiter]),
    // The live log store: an empty log.
    [`${LOG}&after=0&limit=1`]: () => logPage([], null, null),
    // The replay loader: the latest snapshot, then everything after it.
    [`${LOG}&after=0&limit=5&kind=snapshot`]: () =>
      logPage(snapshots, snapshots.length ? 10 : null, snapshots.length ? 24 : null),
    [`${LOG}&after=10&limit=500`]: () => logPage(throwEntries, 10, 24),
  });
}

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

async function openArea() {
  const view = renderAt(AREA_PATH);
  await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
  await waitFor(() => expect(view.container.querySelector(`[data-entity-id="${ORBITER}"]`)).not.toBeNull());
  return view;
}

const room = (container: HTMLElement, id: string) => container.querySelector(`[data-entity-id="${id}"]`);

describe('area view replay', () => {
  it('enters replay, steps to the damage entry, sees the pebble, and returns to live', async () => {
    mockWorld();
    const { container } = await openArea();
    expect(screen.queryByRole('region', { name: 'Replay controls' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Replay' }));
    const controls = await screen.findByRole('region', { name: 'Replay controls' }, { timeout: 4000 });
    expect(within(controls).getByRole('status')).toHaveTextContent('REPLAY');
    expect(container.querySelector('.area-room')).toHaveAttribute('data-replay', 'true');

    // At the snapshot: the replayed room has the orbiter at (2, 2), not the live (8, 4); no pebble.
    expect(room(container, PEBBLE)).toBeNull();
    expect(room(container, ORBITER)?.querySelector('rect, circle')).toHaveAttribute('cx', '2');

    // Next action: the throw (seq 13); then four entries on: create pebble (14), fly start, fly end, damage (17).
    fireEvent.click(screen.getByRole('button', { name: 'Next action' }));
    expect(controls).toHaveTextContent('entry 13');
    for (let i = 0; i < 4; i += 1) fireEvent.click(screen.getByRole('button', { name: 'Next entry' }));
    expect(controls).toHaveTextContent('entry 17');
    expect(room(container, PEBBLE)).not.toBeNull();
    const panel = within(screen.getByRole('complementary', { name: 'Resources' }));
    expect(panel.getAllByText(/"hp": 97.5/)).toHaveLength(1);

    // The log panel shows the replay's entries with the current one marked.
    const current = container.querySelector('.log-entry[aria-current="true"]');
    expect(current).toHaveAttribute('data-seq', '17');

    // Clicking an entry jumps there: before the creation there is no pebble.
    const entry11 = container.querySelector('.log-entry[data-seq="11"]');
    expect(entry11).not.toBeNull();
    fireEvent.click(entry11!);
    expect(controls).toHaveTextContent('entry 11');
    expect(room(container, PEBBLE)).toBeNull();

    // Keyboard: Enter on an entry jumps too.
    const entry14 = container.querySelector('.log-entry[data-seq="14"]') as HTMLElement;
    fireEvent.keyDown(entry14, { key: 'Enter' });
    expect(controls).toHaveTextContent('entry 14');
    expect(room(container, PEBBLE)).not.toBeNull();

    // Back to live: instantly the live room, no controls.
    fireEvent.click(screen.getByRole('button', { name: 'Back to live' }));
    expect(screen.queryByRole('region', { name: 'Replay controls' })).toBeNull();
    expect(container.querySelector('.area-room')).not.toHaveAttribute('data-replay');
    expect(room(container, PEBBLE)).toBeNull();
    expect(room(container, ORBITER)?.querySelector('rect, circle')).toHaveAttribute('cx', '8');
    expect(screen.getByRole('timer', { name: 'Game time' })).toBeInTheDocument();
  });

  it('says so when the log has no snapshot', async () => {
    mockWorld([]);
    await openArea();
    fireEvent.click(screen.getByRole('button', { name: 'Replay' }));
    expect(await screen.findByRole('alert', undefined, { timeout: 4000 })).toHaveTextContent('no snapshot');
    expect(screen.queryByRole('region', { name: 'Replay controls' })).toBeNull();
  });

  it('shows an error when the fetch fails', async () => {
    const api = mockWorld();
    await openArea();
    api.fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (path.includes('kind=snapshot')) return json(500, { type: 'about:blank', status: 500, title: 'boom' });
      return json(200, { entries: [], first: null, last: null });
    });
    fireEvent.click(screen.getByRole('button', { name: 'Replay' }));
    expect(await screen.findByRole('alert', undefined, { timeout: 4000 })).toBeInTheDocument();
  });

  it('can be cancelled while loading', async () => {
    const api = mockWorld();
    await openArea();
    api.fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
          if (!path.includes('kind=snapshot')) return;
          init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Replay' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(await screen.findByRole('button', { name: 'Replay' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('Back to live works at once while playing at 16x', async () => {
    mockWorld();
    const { container } = await openArea();

    fireEvent.click(screen.getByRole('button', { name: 'Replay' }));
    const controls = await screen.findByRole('region', { name: 'Replay controls' }, { timeout: 4000 });

    // Set speed to 16x
    fireEvent.click(screen.getByRole('button', { name: '16×' }));

    // Start playing
    fireEvent.click(screen.getByRole('button', { name: 'Play' }));

    // Wait a short time so the playback timer fires several times
    await new Promise((r) => setTimeout(r, 300));

    // Verify playback is running (Pause button should exist)
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();

    // Immediately click "Back to live"
    fireEvent.click(screen.getByRole('button', { name: 'Back to live' }));

    // Expect immediate effects:
    // 1. The replay controls region is gone
    expect(screen.queryByRole('region', { name: 'Replay controls' })).toBeNull();

    // 2. Container .area-room has no data-replay attribute
    expect(container.querySelector('.area-room')).not.toHaveAttribute('data-replay');

    // 3. The live game time timer role 'timer' name 'Game time' is present
    expect(screen.getByRole('timer', { name: 'Game time' })).toBeInTheDocument();

    // Wait another 300ms to ensure the disposed player doesn't bring anything back
    await new Promise((r) => setTimeout(r, 300));

    // Verify the region is still gone
    expect(screen.queryByRole('region', { name: 'Replay controls' })).toBeNull();

    // Verify that the "Replay" button is shown again
    expect(screen.getByRole('button', { name: 'Replay' })).toBeInTheDocument();
  });
});
