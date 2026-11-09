import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fillLogin, submit } from '../test/forms.ts';
import { ALICE, json, mockApi, problem, storeTestSession, tokenResponse } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';

describe('protected routes', () => {
  it('redirects to login with next, then returns there after login', async () => {
    mockApi({
      'POST /api/auth/login': () => tokenResponse(),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/play?seed=7');

    expect(screen.getByRole('heading', { level: 1, name: 'Log in' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/login?next=%2Fplay%3Fseed%3D7');

    fillLogin('alice', 'correct horse');
    submit('Log in');

    expect(await screen.findByRole('heading', { level: 1, name: 'Play' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/play?seed=7');
  });

  it('renders the protected view for a logged-in player, after the session check', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/play');

    // While checking: neither the view nor a redirect.
    expect(screen.getAllByText('Checking session…').length).toBeGreaterThan(0);
    expect(currentLocation()).toBe('/play');

    expect(await screen.findByRole('heading', { name: 'Play' })).toBeInTheDocument();
    expect(screen.getByText(/not ready for you yet, alice/)).toBeInTheDocument();
    expect(currentLocation()).toBe('/play');
  });

  it('sends a logged-in player away from /login to the next target', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/login?next=%2Fplay');
    expect(await screen.findByRole('heading', { name: 'Play' })).toBeInTheDocument();
  });

  it('sends a player whose stored token is rejected to login, keeping next', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => problem(401, { detail: 'Invalid token' }) });
    renderAt('/play');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/login?next=%2Fplay');
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended.');
  });

  it('after logging out, /play asks to log in again', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/play');
    expect(await screen.findByRole('heading', { name: 'Play' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
    expect(currentLocation()).toBe('/');
    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: 'Log in' }));
    expect(screen.queryByRole('status')).toBeNull(); // no "session ended" notice after a logout
  });
});
