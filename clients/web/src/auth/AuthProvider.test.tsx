import { act, render, screen, waitFor, within } from '@testing-library/react';
import { StrictMode, useState } from 'react';
import { MemoryRouter } from 'react-router';
import { App } from '../App.tsx';
import { describe, expect, it, vi } from 'vitest';
import { errorMessage } from '../api/client.ts';
import {
  ALICE,
  deferred,
  json,
  mockApi,
  problem,
  readStoredSession,
  storeTestSession,
} from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';
import { AuthProvider, useAuth } from './AuthProvider.tsx';
import { SESSION_STORAGE_KEY } from './sessionStore.ts';

function header() {
  return within(screen.getByRole('banner'));
}

describe('session restore', () => {
  it('validates a stored token via /me, showing "checking" rather than logged out meanwhile', async () => {
    storeTestSession({ token: 'stored-jwt' });
    const me = deferred<Response>();
    const api = mockApi({ 'GET /api/auth/me': () => me.promise });
    renderAt('/');

    expect(header().getByText('Checking session…')).toBeInTheDocument();
    expect(header().queryByRole('link', { name: 'Log in' })).toBeNull();
    expect(screen.queryByText(/to play\./)).toBeNull();
    expect(screen.queryByText(/Welcome/)).toBeNull();

    await act(async () => {
      me.resolve(json(200, { ...ALICE, username: 'Alice' }));
    });

    expect(await screen.findByText(/Welcome, Alice\./)).toBeInTheDocument();
    expect(api.requests.map((r) => r.headers.get('Authorization'))).toContain('Bearer stored-jwt');
    // The fresh user from /me replaces the stored copy.
    expect(readStoredSession()?.user.username).toBe('Alice');
  });

  it('restores the session under StrictMode (effects run twice)', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    render(
      <StrictMode>
        <MemoryRouter>
          <App />
        </MemoryRouter>
      </StrictMode>,
    );
    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
  });

  it('clears the session when /me answers 401', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => problem(401, { detail: 'Invalid token' }) });
    renderAt('/');

    expect(await header().findByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByText(/to play\./)).toBeInTheDocument();
    expect(readStoredSession()).toBeNull();
  });

  it('keeps the stored session when /me cannot be reached (outage, not a bad token)', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => Promise.reject(new TypeError('Failed to fetch')) });
    renderAt('/');

    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
    expect(readStoredSession()).not.toBeNull();
  });

  it('clears an already expired stored token on load without calling the server', () => {
    storeTestSession({ expiresAt: new Date(Date.now() - 1000).toISOString() });
    const api = mockApi({});
    renderAt('/');

    expect(header().getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(api.requests).toHaveLength(0);
  });

  it('ends the session when the token expires while the app is running', async () => {
    storeTestSession({ expiresAt: new Date(Date.now() + 300).toISOString() });
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/');

    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
    expect(await header().findByRole('link', { name: 'Log in' }, { timeout: 2000 })).toBeInTheDocument();
    expect(readStoredSession()).toBeNull();
  });

  it('re-checks expiry when the tab regains focus (timers can be paused)', async () => {
    const session = storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/');
    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();

    const later = Date.parse(session.expiresAt) + 1;
    vi.spyOn(Date, 'now').mockReturnValue(later);
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    expect(header().getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(readStoredSession()).toBeNull();
  });

  it('works when sessionStorage throws', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    renderAt('/');
    expect(header().getByRole('link', { name: 'Log in' })).toBeInTheDocument();
  });
});

describe('logout', () => {
  it('clears the session client side and shows the logged-out header', async () => {
    storeTestSession();
    const api = mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/play');
    expect(await screen.findByRole('heading', { name: 'Play' })).toBeInTheDocument();

    act(() => {
      header().getByRole('button', { name: 'Log out' }).click();
    });

    expect(readStoredSession()).toBeNull();
    expect(header().getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(header().getByRole('link', { name: 'Register' })).toBeInTheDocument();
    expect(header().queryByText('alice')).toBeNull();
    expect(currentLocation()).toBe('/');
    // No server call: JWTs are not revocable.
    expect(api.requests.map((r) => r.path)).toEqual(['/api/auth/me']);
  });
});

describe('authRequest', () => {
  function Probe() {
    const { authRequest, status } = useAuth();
    const [result, setResult] = useState('');
    return (
      <>
        <p>status: {status}</p>
        <button
          onClick={() => {
            authRequest<{ hp: number }>('/api/world/me').then(
              (body) => setResult(`hp ${body.hp}`),
              (err: unknown) => setResult(errorMessage(err)),
            );
          }}
        >
          call
        </button>
        <p>result: {result}</p>
      </>
    );
  }

  it('sends the Bearer token, and a 401 ends the session', async () => {
    storeTestSession({ token: 'stored-jwt' });
    const api = mockApi({
      'GET /api/auth/me': () => json(200, ALICE),
      'GET /api/world/me': [
        () => json(200, { hp: 4.2 }),
        () => problem(401, { detail: 'Token expired' }),
      ],
    });
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(await screen.findByText('status: authenticated')).toBeInTheDocument();

    screen.getByRole('button', { name: 'call' }).click();
    expect(await screen.findByText('result: hp 4.2')).toBeInTheDocument();
    const worldCall = api.requests.find((r) => r.path === '/api/world/me');
    expect(worldCall?.headers.get('Authorization')).toBe('Bearer stored-jwt');

    screen.getByRole('button', { name: 'call' }).click();
    expect(await screen.findByText('result: Token expired')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('status: anonymous')).toBeInTheDocument());
    expect(readStoredSession()).toBeNull();
  });
});
