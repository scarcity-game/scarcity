import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import { StubWebSocket } from '../test/stubWebSocket.ts';

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const AREA = 'area:00000000-0000-4000-8000-000000000002';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const MOUSE = 'entity:00000000-0000-4000-8000-000000000004';
const enc = encodeURIComponent;
const AREA_PATH =
  '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000002';

const area = {
  ...{ id: AREA, resourceKind: 'area', version: 3, children: [PILLAR, MOUSE] },
  ...{ name: 'Test room', width: 10, height: 8, time: 12.5, depth: 1 },
};
const pillar = {
  ...{ id: PILLAR, resourceKind: 'entity', version: 1, children: [] },
  ...{ entityKind: 'fixed', prototype: 'pillar', name: 'pillar', position: { x: 5, y: 4 } },
  ...{ mass: 6360000.0, radius: 0.5, overlap: false, damageable: true, hp: 100.0 },
};
const mouse = {
  ...{ id: MOUSE, resourceKind: 'entity', version: 7, children: [] },
  ...{ entityKind: 'mob', prototype: 'mouse', name: 'mouse', position: { x: 2, y: 2 } },
  ...{ mass: 20.0, radius: 0.04, speed: 0.5, overlap: false, damageable: true, hp: 3.0 },
  behaviour: { kind: 'random' },
};
const page = (items: unknown[]) => json(200, { items, page: 0, size: 200, total: items.length });

beforeEach(() => {
  StubWebSocket.reset();
  vi.stubGlobal('WebSocket', StubWebSocket);
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  storeTestSession({ token: 'jwt-alice' });
});
afterEach(() => vi.unstubAllGlobals());

function mockWorld() {
  const game = { id: GAME, resourceKind: 'game', version: 2, children: [AREA], name: 'G' };
  return mockApi({
    'GET /api/auth/me': () => json(200, ALICE),
    [`GET /api/world/resources/${enc(GAME)}`]: () => json(200, game),
    [`GET /api/world/resources/${enc(AREA)}`]: () => json(200, area),
    [`GET /api/world/resources/${enc(AREA)}/children?size=200&page=0`]: () => page([pillar, mouse]),
  });
}

async function openArea() {
  mockWorld();
  const { container } = renderAt(AREA_PATH);
  await screen.findByRole('heading', { level: 1, name: 'Test room' }, { timeout: 4000 });
  await waitFor(() =>
    expect(container.querySelector(`[data-entity-id="${MOUSE}"]`)).not.toBeNull(),
  );
  const mark = container.querySelector(`[data-entity-id="${MOUSE}"]`) as Element;
  const inspector = within(screen.getByRole('complementary', { name: 'Inspector' }));
  return { container, mark, inspector };
}

const fact = (scope: HTMLElement, label: string) =>
  scope.querySelector(`[data-fact="${label}"] dd`)?.textContent;

describe('area view selection', () => {
  it('draws no labels, and shows the area and its entities in the inspector', async () => {
    const { container, inspector } = await openArea();
    expect(container.querySelector('.room-svg text')).toBeNull();
    const panel = screen.getByRole('complementary', { name: 'Inspector' });
    expect(fact(panel, 'size')).toBe('10 × 8 m');
    expect(fact(panel, 'depth')).toBe('1');
    expect(inspector.getByRole('button', { name: 'mouse' })).toBeInTheDocument();
  });

  it('shows the entity panel as a card on hover, and hides it on leave', async () => {
    const { mark } = await openArea();
    fireEvent.pointerEnter(mark);
    const card = screen.getByRole('tooltip');
    expect(fact(card, 'mass')).toBe('20 g');
    expect(fact(card, 'diameter')).toBe('0.08 m');
    expect(fact(card, 'controller')).toBe('behaviour: random');
    fireEvent.pointerLeave(mark);
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it('selects on click into the dock, goes up to the area, and Esc clears', async () => {
    const { mark } = await openArea();
    const panel = screen.getByRole('complementary', { name: 'Inspector' });
    fireEvent.click(mark);
    expect(fact(panel, 'name')).toBe('mouse');
    expect(fact(panel, 'version')).toBe('7');
    fireEvent.click(within(panel).getByRole('button', { name: 'Show area: Test room' }));
    expect(fact(panel, 'size')).toBe('10 × 8 m');
    fireEvent.click(within(panel).getByRole('button', { name: 'pillar' }));
    expect(fact(panel, 'hp')).toBe('100');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(fact(panel, 'size')).toBe('10 × 8 m');
  });

  it('clears the selection on a click on the empty map', async () => {
    const { mark } = await openArea();
    const panel = screen.getByRole('complementary', { name: 'Inspector' });
    fireEvent.click(mark);
    expect(fact(panel, 'name')).toBe('mouse');
    fireEvent.click(screen.getByRole('img', { name: 'Room' }));
    expect(fact(panel, 'name')).toBe('Test room');
  });
});
