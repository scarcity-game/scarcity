import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';

const PROFILES = {
  profiles: [
    {
      name: 'arena',
      options: {
        mobs: [
          { prototype: 'goblin', name: 'goblin', faction: 'goblin' },
          { prototype: 'wild-dog', name: 'wild dog', faction: 'beast' },
        ],
        maxDensPerMob: 4,
      },
    },
  ],
};
const GAME_UUID = '00000000-0000-4000-8000-0000000000aa';
const MADE = { game: `game:${GAME_UUID}`, area: 'area:x', entity: 'entity:y' };
const base = {
  'GET /api/auth/me': () => json(200, ALICE),
  'GET /api/world/games/profiles': () => json(200, PROFILES),
};

beforeEach(() => storeTestSession({ token: 'jwt-alice' }));
afterEach(() => vi.unstubAllGlobals());

const goblins = () => screen.getByLabelText(/Dens of goblin/);
const dogs = () => screen.getByLabelText(/Dens of wild dog/);
const submit = () => fireEvent.click(screen.getByRole('button', { name: 'Create game' }));
const open = async () => {
  renderAt('/games/new');
  await screen.findByLabelText(/Dens of goblin/);
};
const posted = (api: ReturnType<typeof mockApi>) => api.requests.find((r) => r.method === 'POST');

describe('the games list', () => {
  it('has a New game button to the form', async () => {
    mockApi({ ...base, 'GET /api/world/resources?type=game': () => json(200, { items: [] }) });
    renderAt('/games');
    fireEvent.click(await screen.findByRole('link', { name: 'New game' }));
    await waitFor(() => expect(currentLocation()).toBe('/games/new'));
  });
});

describe('the new game form', () => {
  it('is built from the profile: one count per mob, with its limit', async () => {
    mockApi(base);
    await open();
    expect(goblins()).toHaveValue(1);
    expect(dogs()).toHaveValue(0);
    expect(dogs()).toHaveAttribute('max', '4');
  });

  it('posts the dens, leaves out a mob with count 0 and opens the game', async () => {
    const api = mockApi({ ...base, 'POST /api/world/games': () => json(201, MADE) });
    await open();
    fireEvent.change(goblins(), { target: { value: '2' } });
    submit();
    await waitFor(() => expect(currentLocation()).toBe(`/games/${GAME_UUID}`));
    expect(posted(api)?.body).toEqual({
      profile: 'arena',
      options: { dens: [{ prototype: 'goblin', count: 2 }] },
    });
    expect(posted(api)?.headers.get('Authorization')).toBe('Bearer jwt-alice');
  });

  it('sends both mobs when both are chosen', async () => {
    const api = mockApi({ ...base, 'POST /api/world/games': () => json(201, MADE) });
    await open();
    fireEvent.change(dogs(), { target: { value: '1' } });
    submit();
    await waitFor(() => expect(currentLocation()).toBe(`/games/${GAME_UUID}`));
    expect(posted(api)?.body).toEqual({
      profile: 'arena',
      options: {
        dens: [
          { prototype: 'goblin', count: 1 },
          { prototype: 'wild-dog', count: 1 },
        ],
      },
    });
  });
});

describe('the new game form errors', () => {
  it('asks for a den when all counts are 0, without a request', async () => {
    const api = mockApi(base);
    await open();
    fireEvent.change(goblins(), { target: { value: '0' } });
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose at least one den.');
    expect(posted(api)).toBeUndefined();
  });

  it('rejects a count over the limit', async () => {
    mockApi(base);
    await open();
    fireEvent.change(goblins(), { target: { value: '5' } });
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'goblin dens must be a whole number from 0 to 4',
    );
  });

  it('shows the message of a 400', async () => {
    mockApi({
      ...base,
      'POST /api/world/games': () => json(400, { error: 'dens: unknown prototype' }),
    });
    await open();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('dens: unknown prototype');
    expect(currentLocation()).toBe('/games/new');
    expect(screen.getByRole('button', { name: 'Create game' })).toBeEnabled();
  });

  it('says something for a 403', async () => {
    mockApi({ ...base, 'POST /api/world/games': () => json(403, {}) });
    await open();
    submit();
    await waitFor(() => expect(screen.getByRole('alert')).not.toBeEmptyDOMElement());
  });

  it('shows an error when the profiles cannot be loaded', async () => {
    mockApi({
      'GET /api/auth/me': () => json(200, ALICE),
      'GET /api/world/games/profiles': () => json(500, {}),
    });
    renderAt('/games/new');
    expect(await screen.findByText(/HTTP 500/)).toBeInTheDocument();
  });
});
