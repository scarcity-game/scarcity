import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const SPECTATOR = 'entity:00000000-0000-4000-8000-000000000006';
const enc = encodeURIComponent;
const AREA_PATH =
  '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000002';
const INTENTS = 'POST /api/world/intents';

const mine = { kind: 'user', sub: ALICE.id };
const areaRes = (activeEntity: unknown) => ({
  id: AREA,
  resourceKind: 'area',
  version: 3,
  children: [SPECTATOR],
  name: 'Arena',
  time: 10.0,
  activeEntity,
});
const spectator = {
  id: SPECTATOR,
  resourceKind: 'entity',
  version: 5,
  children: [],
  entityKind: 'player',
  prototype: 'spectator',
  name: 'spectator',
  position: { x: 20, y: 20 },
  radius: 0.3,
  speed: 1,
  stepReach: 5,
  vision: 10,
  damageable: false,
  actions: ['step', 'wait'],
  controller: mine,
};
const myTurn = { entity: SPECTATOR, controller: mine, since: 0 };

const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

function mockWorld(area: unknown, intents?: Parameters<typeof mockApi>[0]) {
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources?type=game`]: () => page([]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, { id: GAME, resourceKind: 'game', version: 2, children: [AREA], name: 'G' }),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, area),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([spectator]),
    ...intents,
  });
}

const accepted = () => json(200, { area: AREA, time: 11, logSeq: 9, version: 6 });

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

/**
 * The map follows the spectator framed to its vision: viewBox `10 -30 20 20`. At 200 × 200 px that
 * is 10 px a metre, and the spectator at (20, 20) is at pixel (100, 100); y is up in the world.
 */
async function openArea() {
  renderAt(AREA_PATH);
  await screen.findByRole('region', { name: 'Your entity' });
  act(() => StubWebSocket.last.open());
  const svg = screen.getByRole('img', { name: 'Room' });
  svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 200 }) as DOMRect;
  return svg;
}

const moveButton = () => screen.getByRole('button', { name: 'Move' });
const intentPosts = (api: ReturnType<typeof mockWorld>) =>
  api.requests.filter((r) => r.path === '/api/world/intents');
const reachCircle = (svg: Element) => svg.querySelector('.move-reach');

describe('move mode', () => {
  it('is toggled by Move, which shows the reach round my entity; Esc leaves it', async () => {
    mockWorld(areaRes(myTurn));
    const svg = await openArea();
    expect(moveButton()).toHaveAttribute('aria-pressed', 'false');
    expect(reachCircle(svg)).toBeNull();
    fireEvent.click(moveButton());
    expect(moveButton()).toHaveAttribute('aria-pressed', 'true');
    const circle = reachCircle(svg)!;
    expect([
      circle.getAttribute('cx'),
      circle.getAttribute('cy'),
      circle.getAttribute('r'),
    ]).toEqual(['20', '-20', '5']);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(moveButton()).toHaveAttribute('aria-pressed', 'false');
    expect(reachCircle(svg)).toBeNull();
  });

  it('previews the step under the pointer, with its distance and time', async () => {
    mockWorld(areaRes(myTurn));
    const svg = await openArea();
    fireEvent.click(moveButton());
    fireEvent.pointerMove(svg, { pointerId: 1, clientX: 130, clientY: 100 });
    expect(screen.getByText('3 m, 3 s')).toBeInTheDocument();
    const line = svg.querySelector('.move-preview line')!;
    expect([line.getAttribute('x2'), line.getAttribute('y2')]).toEqual(['23', '-20']);
    fireEvent.pointerMove(svg, { pointerId: 1, clientX: 100, clientY: 20 });
    expect(screen.getByText('5 m, 5 s (the reach)')).toBeInTheDocument();
    fireEvent.pointerLeave(svg);
    expect(svg.querySelector('.move-preview')).toBeNull();
  });

  it('steps toward a click, pulled back to the reach, on the entity version', async () => {
    const api = mockWorld(areaRes(myTurn), { [INTENTS]: accepted });
    const svg = await openArea();
    fireEvent.click(moveButton());
    fireEvent.click(svg, { clientX: 180, clientY: 100 });
    await waitFor(() => expect(intentPosts(api)).toHaveLength(1));
    expect(intentPosts(api)[0]!.body).toEqual({
      entity: SPECTATOR,
      actionSpec: { type: 'step', params: { to: { x: 25, y: 20 } } },
      ifVersion: 5,
    });
    expect(moveButton()).toHaveAttribute('aria-pressed', 'true');
  });

  it('steps 1 m with an arrow key, and the arrows no longer pan the map', async () => {
    const api = mockWorld(areaRes(myTurn), { [INTENTS]: accepted });
    const svg = await openArea();
    const box = svg.getAttribute('viewBox');
    fireEvent.click(moveButton());
    fireEvent.keyDown(svg, { key: 'ArrowUp' });
    await waitFor(() => expect(intentPosts(api)).toHaveLength(1));
    expect(intentPosts(api)[0]!.body).toMatchObject({
      actionSpec: { type: 'step', params: { to: { x: 20, y: 21 } } },
    });
    expect(svg.getAttribute('viewBox')).toBe(box);
    expect(screen.getByRole('button', { name: 'Centre' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('leaves the arrows to a form field', async () => {
    const api = mockWorld(areaRes(myTurn), { [INTENTS]: accepted });
    await openArea();
    fireEvent.click(moveButton());
    fireEvent.keyDown(screen.getByLabelText('Wait for'), { key: 'ArrowDown' });
    expect(intentPosts(api)).toHaveLength(0);
  });

  it('shows a refusal in the action bar', async () => {
    mockWorld(areaRes(myTurn), { [INTENTS]: () => json(422, { error: 'step too long' }) });
    const svg = await openArea();
    fireEvent.click(moveButton());
    fireEvent.click(svg, { clientX: 110, clientY: 100 });
    expect(await screen.findByRole('alert')).toHaveTextContent('spectator: step too long');
  });

  it('draws nothing and steps nowhere when it is not my turn', async () => {
    const other = { entity: 'entity:other', controller: { kind: 'service', sub: 'brawler' } };
    const api = mockWorld(areaRes(other), { [INTENTS]: accepted });
    const svg = await openArea();
    fireEvent.click(moveButton());
    expect(screen.getByText('Waiting for your turn')).toBeInTheDocument();
    expect(reachCircle(svg)).toBeNull();
    fireEvent.click(svg, { clientX: 110, clientY: 100 });
    fireEvent.keyDown(document.body, { key: 'ArrowUp' });
    expect(intentPosts(api)).toHaveLength(0);
  });
});
