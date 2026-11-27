import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { Inspector } from './Inspector.tsx';

// A plain function over a swappable reply: a vi.fn would flag the rejection of a promise it recorded.
let reply: () => Promise<unknown> = () => Promise.resolve(undefined);
const paths: string[] = [];
const authRequest = (path: string) => {
  paths.push(path);
  return reply();
};
vi.mock('../auth/AuthProvider.tsx', () => ({ useAuth: () => ({ authRequest }) }));

const GAME = 'game:00000000-0000-4000-8000-000000000001';
const HERE = 'area:00000000-0000-4000-8000-000000000011';
const THERE = 'area:00000000-0000-4000-8000-000000000012';
const PAIRED = 'entity:00000000-0000-4000-8000-000000000022';

const area: Resource = {
  id: HERE,
  resourceKind: 'area',
  version: 1,
  children: ['entity:2'],
  name: 'Level 1',
};
const stairs = (to: string | null, toArea: string | null = to && THERE): Resource => ({
  id: 'entity:2',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'fixed',
  prototype: 'stairs-down',
  name: 'stairs down',
  to,
  toArea,
});
const there: Resource = {
  id: THERE,
  resourceKind: 'area',
  version: 1,
  children: [PAIRED],
  name: 'Level 2',
};

function show(selected: Resource) {
  render(
    <MemoryRouter>
      <Inspector
        gameId={GAME}
        selected={selected}
        area={area}
        resources={
          new Map([
            [area.id, area],
            [selected.id, selected],
          ])
        }
        onSelect={() => {}}
        isStale={() => false}
      />
    </MemoryRouter>,
  );
  return screen.getByRole('complementary', { name: 'Inspector' });
}

describe('Inspector with stairs selected, the level beyond', () => {
  beforeEach(() => {
    paths.length = 0;
  });

  it('links to the area the stairs name and names it in the leads fact, with one request', async () => {
    reply = () => Promise.resolve(there);
    const panel = show(stairs(PAIRED));
    expect(within(panel).getByRole('status')).toHaveTextContent('Finding the level');
    const link = await within(panel).findByRole('link', { name: 'Go to Level 2' });
    expect(link).toHaveAttribute(
      'href',
      '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000012',
    );
    expect(within(panel).getByText('down to Level 2')).toBeInTheDocument();
    expect(paths).toEqual([`/api/world/resources/${encodeURIComponent(THERE)}`]);
  });

  it('shows a plain explanation, no link and no request, when no level exists yet', () => {
    const panel = show(stairs(null));
    expect(within(panel).getByText(/No level there yet/)).toBeInTheDocument();
    expect(within(panel).queryByRole('link')).toBeNull();
    expect(paths).toEqual([]);
  });

  it('shows an error, no link, when the lookup fails', async () => {
    reply = () => Promise.reject(new TypeError('boom'));
    const panel = show(stairs(PAIRED));
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    expect(within(panel).queryByRole('link')).toBeNull();
  });

  it('shows an error when the area the stairs name is not there', async () => {
    reply = () => Promise.resolve(undefined);
    const panel = show(stairs(PAIRED));
    await waitFor(() => expect(within(panel).getByRole('alert')).toHaveTextContent('not found'));
  });
});
