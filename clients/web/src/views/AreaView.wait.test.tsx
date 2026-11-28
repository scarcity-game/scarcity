import { screen, waitFor, act, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const SPECTATOR = 'entity:00000000-0000-4000-8000-000000000006';
const MOUSE = 'entity:00000000-0000-4000-8000-000000000007';
const enc = encodeURIComponent;
const AREA_PATH =
  '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000002';

const mine = { kind: 'user', sub: ALICE.id };
const active = (entity: string, controller: unknown) => ({ entity, controller, since: 0 });

const areaRes = (version: number, activeEntity?: unknown) => ({
  id: AREA,
  resourceKind: 'area',
  version,
  children: [SPECTATOR, MOUSE],
  name: 'Arena',
  time: 10.0,
  ...(activeEntity === undefined ? {} : { activeEntity }),
});
const spectator = (version: number, controller: unknown = mine) => ({
  id: SPECTATOR,
  resourceKind: 'entity',
  version,
  children: [],
  entityKind: 'player',
  name: 'spectator',
  position: { x: 1, y: 1 },
  actions: ['step', 'wait'],
  controller,
});
const mouse = {
  id: MOUSE,
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: 'mouse',
  position: { x: 3, y: 3 },
  controller: { kind: 'service', principal: 'microbe' },
};

const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });
const INTENTS = 'POST /api/world/intents';

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

function mockWorld(area: unknown, spec: unknown, intents?: Parameters<typeof mockApi>[0]) {
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources?type=game`]: () => page([]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, { id: GAME, resourceKind: 'game', version: 2, children: [AREA], name: 'G' }),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, area),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([spec, mouse]),
    ...intents,
  });
}

async function openArea() {
  renderAt(AREA_PATH);
  await screen.findByRole('heading', { level: 1, name: 'Arena' });
  await screen.findByText(/turn/);
  const ws = StubWebSocket.last;
  act(() => ws.open());
  act(() => ws.receive({ type: 'ready', user: ALICE.id, username: 'alice' }));
  return ws;
}

const intentPosts = (api: ReturnType<typeof mockWorld>) =>
  api.requests.filter((r) => r.path === '/api/world/intents');

const waitButton = () => screen.getByRole('button', { name: 'Wait' });

function setActive(ws: StubWebSocket, version: number, activeEntity: unknown) {
  act(() =>
    ws.receive({
      type: 'changes',
      area: AREA,
      changes: [{ id: AREA, resourceKind: 'area', version, set: { activeEntity } }],
    }),
  );
}

describe('the wait control: whose turn', () => {
  it('is enabled when the area names my entity as active', async () => {
    mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5));
    await openArea();
    expect(screen.getByText('Your turn')).toBeInTheDocument();
    expect(waitButton()).toBeEnabled();
  });

  it('is disabled when another entity is active', async () => {
    mockWorld(areaRes(3, active(MOUSE, mouse.controller)), spectator(5));
    await openArea();
    expect(screen.getByText('Not your turn')).toBeInTheDocument();
    expect(waitButton()).toBeDisabled();
  });

  it('follows the area live', async () => {
    mockWorld(areaRes(3, active(MOUSE, mouse.controller)), spectator(5));
    const ws = await openArea();
    expect(waitButton()).toBeDisabled();
    setActive(ws, 4, active(SPECTATOR, mine));
    await waitFor(() => expect(waitButton()).toBeEnabled());
    setActive(ws, 5, active(MOUSE, mouse.controller));
    await waitFor(() => expect(waitButton()).toBeDisabled());
  });

  it('is absent when the entity belongs to someone else', async () => {
    mockWorld(
      areaRes(3, active(MOUSE, mouse.controller)),
      spectator(5, { kind: 'user', sub: 'other' }),
    );
    renderAt(AREA_PATH);
    await screen.findByRole('heading', { level: 1, name: 'Arena' });
    await waitFor(() => expect(screen.queryByText(/Not your turn/)).toBeNull());
    expect(screen.queryByRole('button', { name: 'Wait' })).toBeNull();
  });
});

describe('the wait control: posting', () => {
  it('posts wait with the chosen duration and the entity version', async () => {
    const api = mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => json(200, { area: AREA, time: 20, logSeq: 9, version: 6 }),
    });
    await openArea();
    fireEvent.change(screen.getByLabelText('Wait for'), { target: { value: '15' } });
    fireEvent.click(waitButton());
    await waitFor(() => expect(intentPosts(api)).toHaveLength(1));
    const post = intentPosts(api)[0]!;
    expect(post.method).toBe('POST');
    expect(post.headers.get('Authorization')).toBe('Bearer jwt-alice');
    expect(post.body).toEqual({
      entity: SPECTATOR,
      actionSpec: { type: 'wait', params: { duration: 15 } },
      ifVersion: 5,
    });
  });

  it('defaults to 5 seconds and disables the button while the post is in flight', async () => {
    let answer: (r: Response) => void = () => {};
    const api = mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => new Promise<Response>((resolve) => (answer = resolve)),
    });
    await openArea();
    fireEvent.click(waitButton());
    await waitFor(() => expect(waitButton()).toBeDisabled());
    fireEvent.click(waitButton());
    expect(api.requests.filter((r) => r.path === '/api/world/intents')).toHaveLength(1);
    expect(api.requests.find((r) => r.path === '/api/world/intents')!.body).toMatchObject({
      actionSpec: { params: { duration: 5 } },
    });
    answer(json(200, { area: AREA, time: 20, logSeq: 9, version: 6 }));
    await waitFor(() => expect(waitButton()).toBeEnabled());
  });

  it.each([
    [409, 'entity is not the active one'],
    [422, 'duration must be positive'],
  ])('shows a %i and lets the subscription catch up', async (status, error) => {
    mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => json(status, { error }),
    });
    const ws = await openArea();
    fireEvent.click(waitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
    expect(waitButton()).toBeEnabled();
    setActive(ws, 4, active(MOUSE, mouse.controller));
    await waitFor(() => expect(waitButton()).toBeDisabled());
  });

  it('shows a network failure', async () => {
    mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => Promise.reject(new TypeError('offline')) as never,
    });
    await openArea();
    fireEvent.click(waitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(/network|connect/i);
  });
});

describe('the wait control: auto', () => {
  it('is off by default and waits again each turn once on', async () => {
    const api = mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => json(200, { area: AREA, time: 20, logSeq: 9, version: 6 }),
    });
    const ws = await openArea();
    const posts = () => api.requests.filter((r) => r.path === '/api/world/intents').length;
    const auto = screen.getByRole('checkbox', { name: 'Auto' });
    expect(auto).not.toBeChecked();
    expect(posts()).toBe(0);
    fireEvent.click(auto);
    await waitFor(() => expect(posts()).toBe(1));
    // The view has not caught up with version 6 yet: no second post.
    await new Promise((r) => setTimeout(r, 50));
    expect(posts()).toBe(1);
    act(() =>
      ws.receive({
        type: 'changes',
        area: AREA,
        changes: [
          { id: SPECTATOR, resourceKind: 'entity', version: 6, set: { name: 'spectator' } },
        ],
      }),
    );
    await waitFor(() => expect(posts()).toBe(2));
  });

  it('turns itself off at an error', async () => {
    const api = mockWorld(areaRes(3, active(SPECTATOR, mine)), spectator(5), {
      [INTENTS]: () => json(409, { error: 'stale' }),
    });
    await openArea();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Auto' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('stale');
    expect(screen.getByRole('checkbox', { name: 'Auto' })).not.toBeChecked();
    await new Promise((r) => setTimeout(r, 50));
    expect(api.requests.filter((r) => r.path === '/api/world/intents')).toHaveLength(1);
  });
});
