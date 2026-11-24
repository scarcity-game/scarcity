import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';

const GAME_UUID = '00000000-0000-4000-8000-000000000011';
const GAME = `game:${GAME_UUID}`;
const PATH = `/games/${GAME_UUID}/areas`;
const enc = encodeURIComponent;
const listKey = (page: number, active: boolean) =>
  `GET /api/world/resources?type=area&game=${enc(GAME)}${active ? '&has=activeEntity' : ''}&size=50&page=${page}`;

const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const area = (n: number, extra: Record<string, unknown> = {}) => ({
  id: `area:${uuid(n)}`,
  resourceKind: 'area',
  version: 1,
  children: ['entity:a', 'entity:b'],
  name: `Cellar ${n}`,
  depth: n,
  time: 12.5,
  ...extra,
});
const active = area(1, {
  activeEntity: { entity: 'entity:diver', controller: { kind: 'service', principal: 'diver' } },
});
const pageOf = (items: unknown[], page: number, total: number) =>
  json(200, { items, page, size: 50, total });
const me = () => json(200, ALICE);

beforeEach(() => storeTestSession({ token: 'jwt-alice' }));
afterEach(() => vi.unstubAllGlobals());

describe('area list rows', () => {
  it('shows the active areas by default, with their facts', async () => {
    mockApi({ 'GET /api/auth/me': me, [listKey(0, true)]: () => pageOf([active], 0, 1) });
    renderAt(PATH);
    const link = await screen.findByRole('link', { name: 'Cellar 1' });
    const row = link.closest('li');
    expect(row).toHaveTextContent('depth 1');
    expect(row).toHaveTextContent('time 12.5 s');
    expect(row).toHaveTextContent('2 entities');
    expect(row).toHaveTextContent('Active: entity:diver (service diver)');
    expect(screen.getByRole('radio', { name: 'Active' })).toBeChecked();
    expect(link).toHaveAttribute('href', `/games/${GAME_UUID}/areas/${uuid(1)}`);
  });

  it('links back to the game and shows a failed load', async () => {
    mockApi({ 'GET /api/auth/me': me, [listKey(0, true)]: () => json(500, { error: 'boom' }) });
    renderAt(PATH);
    await waitFor(() => expect(document.querySelector('.form-error')).not.toBeNull());
    expect(screen.getByRole('link', { name: 'Back to game' })).toHaveAttribute(
      'href',
      `/games/${GAME_UUID}`,
    );
  });
});

describe('area list filter and paging', () => {
  it('switches to all areas, and the filter goes to the server', async () => {
    const { requests } = mockApi({
      'GET /api/auth/me': me,
      [listKey(0, true)]: () => pageOf([active], 0, 1),
      [listKey(0, false)]: () => pageOf([active, area(2)], 0, 2),
    });
    renderAt(PATH);
    await screen.findByRole('link', { name: 'Cellar 1' });
    fireEvent.click(screen.getByRole('radio', { name: 'All' }));
    expect(await screen.findByRole('link', { name: 'Cellar 2' })).toBeInTheDocument();
    expect(currentLocation()).toBe(`${PATH}?show=all`);
    expect(requests.some((r) => r.path === listKey(0, false).slice(4))).toBe(true);
    expect(screen.getByText(/Showing 2 of 2/)).toBeInTheDocument();
  });

  it('loads more pages until the total is reached', async () => {
    const first = Array.from({ length: 50 }, (_, i) => area(i + 1));
    mockApi({
      'GET /api/auth/me': me,
      [listKey(0, false)]: () => pageOf(first, 0, 51),
      [listKey(1, false)]: () => pageOf([area(51)], 1, 51),
    });
    renderAt(`${PATH}?show=all`);
    await screen.findByRole('link', { name: 'Cellar 50' });
    expect(screen.getByText(/Showing 50 of 51/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('link', { name: 'Cellar 51' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cellar 1' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });
});

describe('area list empty states', () => {
  it('says there are no active areas and offers All', async () => {
    mockApi({
      'GET /api/auth/me': me,
      [listKey(0, true)]: () => pageOf([], 0, 0),
      [listKey(0, false)]: () => pageOf([area(2)], 0, 1),
    });
    renderAt(PATH);
    expect(await screen.findByText(/No active areas/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show all areas' }));
    expect(await screen.findByRole('link', { name: 'Cellar 2' })).toBeInTheDocument();
  });

  it('says a game has no areas', async () => {
    mockApi({ 'GET /api/auth/me': me, [listKey(0, false)]: () => pageOf([], 0, 0) });
    renderAt(`${PATH}?show=all`);
    expect(await screen.findByText('This game has no areas.')).toBeInTheDocument();
  });
});
