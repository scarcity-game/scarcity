import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fillLogin, submit } from '../test/forms.ts';
import {
  ALICE,
  deferred,
  json,
  mockApi,
  problem,
  readStoredSession,
  tokenResponse,
} from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';

describe('login', () => {
  it('logs in, fetches /me with the Bearer token, stores the session and goes home', async () => {
    const expiresAt = new Date(Date.now() + 3_600_000).toISOString();
    const api = mockApi({
      'POST /api/auth/login': () => tokenResponse('jwt-alice', expiresAt),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/login');

    fillLogin('alice', 'correct horse');
    submit('Log in');

    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
    expect(currentLocation()).toBe('/');
    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'POST /api/auth/login',
      'GET /api/auth/me',
    ]);
    expect(api.requests[0]?.body).toEqual({ username: 'alice', password: 'correct horse' });
    expect(api.requests[0]?.headers.has('Authorization')).toBe(false);
    expect(api.requests[1]?.headers.get('Authorization')).toBe('Bearer jwt-alice');
    expect(readStoredSession()).toEqual({ token: 'jwt-alice', expiresAt, user: ALICE });

    const header = screen.getByRole('banner');
    expect(within(header).getByText('alice')).toBeInTheDocument();
    expect(within(header).getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    expect(within(header).queryByRole('link', { name: 'Log in' })).toBeNull();
  });

  it('shows the 401 problem detail and stays logged out', async () => {
    mockApi({
      'POST /api/auth/login': () =>
        problem(401, { title: 'Unauthorized', detail: 'Invalid username or password' }),
    });
    renderAt('/login');

    fillLogin('alice', 'wrong password');
    submit('Log in');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
    expect(currentLocation()).toBe('/login');
    expect(readStoredSession()).toBeNull();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Log in' })).toBeVisible();
  });

  it('shows "Invalid username or password" for a 401 without a problem body', async () => {
    mockApi({ 'POST /api/auth/login': () => new Response(null, { status: 401 }) });
    renderAt('/login');
    fillLogin('alice', 'wrong password');
    submit('Log in');
    expect(await screen.findByRole('alert')).toHaveTextContent(/^Invalid username or password$/);
  });

  it('handles a non-JSON server error and a network failure', async () => {
    mockApi({
      'POST /api/auth/login': [
        () => new Response('<h1>502 Bad Gateway</h1>', { status: 502, headers: { 'Content-Type': 'text/html' } }),
        () => Promise.reject(new TypeError('Failed to fetch')),
      ],
    });
    renderAt('/login');
    fillLogin('alice', 'some password');

    submit('Log in');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Something went wrong (HTTP 502). Please try again.',
    );

    submit('Log in');
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Could not reach the server.'),
    );
  });

  it('disables the submit button while the request is in flight', async () => {
    const pending = deferred<Response>();
    const api = mockApi({
      'POST /api/auth/login': () => pending.promise,
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/login');
    fillLogin('alice', 'correct horse');
    submit('Log in');

    const button = await screen.findByRole('button', { name: 'Logging in…' });
    expect(button).toBeDisabled();
    submit('Logging in…');
    expect(api.requests).toHaveLength(1);

    pending.resolve(tokenResponse());
    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
  });

  it('marks empty fields invalid with described inline errors, without calling the server', () => {
    const api = mockApi({});
    renderAt('/login');
    submit('Log in');

    const username = screen.getByLabelText('Username');
    const password = screen.getByLabelText('Password');
    expect(username).toHaveAttribute('aria-invalid', 'true');
    expect(username).toHaveAccessibleDescription('Enter your username.');
    expect(password).toHaveAttribute('aria-invalid', 'true');
    expect(password).toHaveAccessibleDescription('Enter your password.');
    expect(username).toHaveFocus();
    expect(api.requests).toHaveLength(0);

    fillLogin('alice', 'x');
    expect(username).toHaveAttribute('aria-invalid', 'false');
    expect(username).not.toHaveAttribute('aria-describedby');
  });

  it('uses the right autocomplete attributes', () => {
    renderAt('/login');
    expect(screen.getByLabelText('Username')).toHaveAttribute('autocomplete', 'username');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
  });

  it('links to register, keeping the return-to target', () => {
    renderAt('/login?next=%2Fplay');
    const link = within(screen.getByRole('main')).getByRole('link', { name: 'Register' });
    expect(link).toHaveAttribute('href', '/register?next=%2Fplay');
  });

  it('ignores an off-site next target after login', async () => {
    mockApi({
      'POST /api/auth/login': () => tokenResponse(),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/login?next=%2F%2Fevil.example%2Fsteal');
    fillLogin('alice', 'correct horse');
    submit('Log in');
    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
    expect(currentLocation()).toBe('/');
  });
});
