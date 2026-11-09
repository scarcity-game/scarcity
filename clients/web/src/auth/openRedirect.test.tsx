// Tester-added: the ?next= return-to target must never take the browser off
// origin, even through the real BrowserRouter (history.pushState, which
// react-router falls back from to location.assign on failure).
import { render, screen, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { BrowserRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from '../App.tsx';
import { fillLogin, submit } from '../test/forms.ts';
import { ALICE, json, mockApi, tokenResponse } from '../test/mockApi.ts';

const ORIGIN = window.location.origin;

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

function renderBrowserAt(url: string) {
  window.history.replaceState(null, '', url);
  return render(
    <StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </StrictMode>,
  );
}

describe('return-to cannot leave the origin', () => {
  it.each([
    '//evil.example/x',
    '/\\evil.example',
    '/.//evil.example',
    '/..//evil.example',
    '/%2F%2Fevil.example',
    '/%5Cevil.example',
    '/%09/evil.example',
    'https:evil.example',
    'https://evil.example',
    '\\/evil.example',
    'javascript:alert(document.domain)',
    '/play/../../..//evil.example',
  ])('next=%j stays on our origin after login (StrictMode, BrowserRouter)', async (next) => {
    const api = mockApi({
      'POST /api/auth/login': () => tokenResponse(),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderBrowserAt(`/login?next=${encodeURIComponent(next)}`);
    fillLogin('alice', 'correct horse');
    submit('Log in');

    await waitFor(() => expect(window.location.pathname).not.toBe('/login'));
    expect(window.location.origin).toBe(ORIGIN);
    expect(new URL(window.location.href).host).not.toContain('evil');
    // Only the two auth calls, both same-origin relative.
    expect(api.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'POST /api/auth/login',
      'GET /api/auth/me',
    ]);
    expect(api.unmatched).toEqual([]);
  });

  it('never puts the token in the URL', async () => {
    mockApi({
      'POST /api/auth/login': () => tokenResponse('secret-jwt-value'),
      'GET /api/auth/me': () => json(200, ALICE),
    });
    renderBrowserAt('/login?next=%2Fplay');
    fillLogin('alice', 'correct horse');
    submit('Log in');
    expect(await screen.findByRole('heading', { level: 1, name: 'Play' })).toBeInTheDocument();
    expect(window.location.href).not.toContain('secret-jwt-value');
    expect(window.location.pathname).toBe('/play');
  });
});
