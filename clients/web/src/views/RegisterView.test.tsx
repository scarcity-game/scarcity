import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fillRegister, submit } from '../test/forms.ts';
import { ALICE, json, mockApi, problem, readStoredSession, tokenResponse } from '../test/mockApi.ts';
import { currentLocation, renderAt } from '../test/renderAt.tsx';

describe('register', () => {
  it('registers, logs in automatically and goes home', async () => {
    const api = mockApi({
      'POST /api/auth/register': () => json(201, ALICE),
      'POST /api/auth/login': () => tokenResponse('jwt-new'),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/register');

    fillRegister('alice', 'correct horse');
    submit('Register');

    expect(await screen.findByText(/Welcome, alice\./)).toBeInTheDocument();
    expect(currentLocation()).toBe('/');
    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'POST /api/auth/register',
      'POST /api/auth/login',
      'GET /api/auth/me',
    ]);
    const credentials = { username: 'alice', password: 'correct horse' };
    expect(api.requests[0]?.body).toEqual(credentials);
    expect(api.requests[1]?.body).toEqual(credentials);
    expect(api.requests[2]?.headers.get('Authorization')).toBe('Bearer jwt-new');
    expect(readStoredSession()?.token).toBe('jwt-new');
  });

  it('goes to the return-to target after register', async () => {
    mockApi({
      'POST /api/auth/register': () => json(201, ALICE),
      'POST /api/auth/login': () => tokenResponse(),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderAt('/register?next=%2Fplay');
    fillRegister('alice', 'correct horse');
    submit('Register');
    expect(await screen.findByRole('heading', { name: 'Play' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/play');
  });

  it('shows the 409 detail when the username is taken', async () => {
    const api = mockApi({
      'POST /api/auth/register': () =>
        problem(409, { title: 'Conflict', detail: 'Username is already taken' }),
    });
    renderAt('/register');
    fillRegister('alice', 'correct horse');
    submit('Register');

    expect(await screen.findByRole('alert')).toHaveTextContent('Username is already taken');
    const username = screen.getByLabelText('Username');
    expect(username).toHaveAttribute('aria-invalid', 'true');
    expect(username).toHaveAccessibleDescription(
      expect.stringContaining('Username is already taken'),
    );
    expect(username).toHaveFocus();
    expect(api.requests).toHaveLength(1); // no login attempt
    expect(screen.getByRole('button', { name: 'Register' })).toBeEnabled();
    expect(readStoredSession()).toBeNull();

    // Editing the username clears its inline error.
    fireEvent.change(username, { target: { value: 'alice2' } });
    expect(username).toHaveAttribute('aria-invalid', 'false');
  });

  it('marks the username "That username is taken." for a 409 without detail', async () => {
    mockApi({ 'POST /api/auth/register': () => new Response(null, { status: 409 }) });
    renderAt('/register');
    fillRegister('alice', 'correct horse');
    submit('Register');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Something went wrong (HTTP 409). Please try again.',
    );
    const username = screen.getByLabelText('Username');
    expect(username).toHaveAttribute('aria-invalid', 'true');
    expect(username).toHaveAccessibleDescription(
      expect.stringContaining('That username is taken.'),
    );
    expect(username).toHaveFocus();
  });

  it('shows the 400 problem detail and the field errors from the server', async () => {
    mockApi({
      'POST /api/auth/register': () =>
        problem(400, {
          title: 'Bad Request',
          detail: 'Request validation failed',
          errors: [{ field: 'password', message: 'must be at most 72 bytes when UTF-8 encoded (bcrypt limit)' }],
        }),
    });
    renderAt('/register');
    fillRegister('alice', 'correct horse');
    submit('Register');

    expect(await screen.findByRole('alert')).toHaveTextContent('Request validation failed');
    const password = screen.getByLabelText('Password');
    expect(password).toHaveAttribute('aria-invalid', 'true');
    expect(password).toHaveAccessibleDescription(
      expect.stringContaining('Password must be at most 72 bytes when UTF-8 encoded (bcrypt limit).'),
    );
  });

  it('falls back to the title when a 400 has no detail', async () => {
    mockApi({ 'POST /api/auth/register': () => problem(400, { title: 'Bad Request' }) });
    renderAt('/register');
    fillRegister('alice', 'correct horse');
    submit('Register');
    expect(await screen.findByRole('alert')).toHaveTextContent(/^Bad Request$/);
  });

  it('says the account exists when the automatic login fails', async () => {
    mockApi({
      'POST /api/auth/register': () => json(201, ALICE),
      'POST /api/auth/login': () => Promise.reject(new TypeError('Failed to fetch')),
    });
    renderAt('/register?next=%2Fplay');
    fillRegister('alice', 'correct horse');
    submit('Register');

    const alert = await screen.findByRole('alert');
    await within(alert).findByText(/Your account was created, but logging in failed/);
    expect(alert).toHaveTextContent('Could not reach the server.');
    expect(within(alert).getByRole('link', { name: 'Log in' })).toHaveAttribute(
      'href',
      '/login?next=%2Fplay',
    );
  });

  it('validates on the client before calling the server', () => {
    const api = mockApi({});
    renderAt('/register');

    fillRegister('al', 'short', 'shorter');
    submit('Register');

    const username = screen.getByLabelText('Username');
    expect(username).toHaveAttribute('aria-invalid', 'true');
    expect(username).toHaveAccessibleDescription(expect.stringContaining('Username must be 3–32 characters long'));
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      expect.stringContaining('Password must be at least 8 characters long'),
    );
    expect(screen.getByLabelText('Confirm password')).toHaveAccessibleDescription(
      'Passwords do not match.',
    );
    expect(username).toHaveFocus();

    fillRegister('al!ce', 'correct horse', 'correct horse');
    submit('Register');
    expect(screen.getByLabelText('Username')).toHaveAccessibleDescription(
      expect.stringContaining('may only use the letters'),
    );
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'false');
    expect(api.requests).toHaveLength(0);
  });

  it('rejects a password over 72 UTF-8 bytes even when it is under 72 characters', () => {
    const api = mockApi({});
    renderAt('/register');
    const password = 'ö'.repeat(40); // 40 characters, 80 bytes
    fillRegister('alice', password);
    submit('Register');

    const input = screen.getByLabelText('Password');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(expect.stringContaining('it is 80 bytes when UTF-8 encoded'));
    expect(input).toHaveAccessibleDescription(expect.stringContaining('emoji take 2–4 bytes each'));
    expect(input).toHaveFocus();
    expect(api.requests).toHaveLength(0);
  });

  it('uses new-password autocomplete and links to login', () => {
    renderAt('/register');
    expect(screen.getByLabelText('Username')).toHaveAttribute('autocomplete', 'username');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByLabelText('Confirm password')).toHaveAttribute('autocomplete', 'new-password');
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Log in' })).toHaveAttribute(
      'href',
      '/login',
    );
  });
});
