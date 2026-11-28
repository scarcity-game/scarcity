import { act, fireEvent, screen, within } from '@testing-library/react';
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

const mine = { kind: 'user', sub: ALICE.id };
const area = {
  id: AREA,
  resourceKind: 'area',
  version: 3,
  children: [SPECTATOR],
  name: 'Arena',
  time: 10.0,
  activeEntity: { entity: SPECTATOR, controller: mine, since: 0 },
};
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

const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
  mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources?type=game`]: () => page([]),
    [`GET /api/world/resources/${enc(GAME)}`]: () =>
      json(200, { id: GAME, resourceKind: 'game', version: 2, children: [AREA], name: 'G' }),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, area),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([spectator]),
  });
});
afterEach(() => vi.unstubAllGlobals());

async function openArea() {
  renderAt(AREA_PATH);
  await screen.findByRole('heading', { level: 1, name: 'Arena' });
  const frame = await screen.findByRole('region', { name: 'Your entity' });
  act(() => StubWebSocket.last.open());
  return frame;
}

const centre = () => screen.getByRole('button', { name: 'Centre' });

describe('the controls for my entity: the frame and the bar', () => {
  it('frames my entity with its turn, speed, reach and vision', async () => {
    const frame = await openArea();
    expect(within(frame).getByText('spectator')).toBeInTheDocument();
    expect(within(frame).getByText('Your turn')).toBeInTheDocument();
    expect(within(frame).getByText('1 m/s')).toBeInTheDocument();
    expect(within(frame).getByText('5 m')).toBeInTheDocument();
    expect(within(frame).getByText('10 m')).toBeInTheDocument();
    expect(within(frame).queryByText('HP')).toBeNull();
  });

  it('has a control per action, in the entity order, and the item panels', async () => {
    await openArea();
    const bar = screen.getByRole('toolbar', { name: 'Actions' });
    expect(within(bar).getByRole('button', { name: 'Move' })).toBeDisabled();
    expect(within(bar).getByRole('button', { name: 'Wait' })).toBeEnabled();
    expect(within(bar).getByRole('button', { name: 'Inventory' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Equipment' })).toBeInTheDocument();
  });

  it('opens one item panel at a time, and closes it again', async () => {
    await openArea();
    const inventory = screen.getByRole('button', { name: 'Inventory' });
    fireEvent.click(inventory);
    expect(inventory).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('region', { name: 'Inventory' })).toHaveTextContent(
      'Carrying nothing.',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Equipment' }));
    expect(screen.queryByRole('region', { name: 'Inventory' })).toBeNull();
    expect(screen.getByRole('region', { name: 'Equipment' })).toHaveTextContent(
      'Nothing equipped.',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Equipment' }));
    expect(screen.queryByRole('region', { name: 'Equipment' })).toBeNull();
  });
});

describe('the controls for my entity: the camera', () => {
  it('follows my entity, framed to its vision', async () => {
    await openArea();
    expect(centre()).toHaveAttribute('aria-pressed', 'true');
    const svg = screen.getByRole('img', { name: 'Room' });
    expect(svg.getAttribute('viewBox')).toBe('10 -30 20 20');
  });

  it('stops following on a pan and centres again on Centre', async () => {
    await openArea();
    fireEvent.keyDown(screen.getByRole('img', { name: 'Room' }), { key: 'ArrowRight' });
    expect(centre()).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(centre());
    expect(centre()).toHaveAttribute('aria-pressed', 'true');
  });
});
