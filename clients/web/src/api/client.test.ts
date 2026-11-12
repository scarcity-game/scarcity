import { describe, expect, it } from 'vitest';
import { json, mockApi, problem } from '../test/mockApi.ts';
import { ApiError, apiRequest, errorMessage, NETWORK_ERROR_MESSAGE } from './client.ts';
import { login, me } from './auth.ts';

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    return err as ApiError;
  }
  throw new Error('expected a rejection');
}

describe('conditional requests', () => {
  it('sends If-None-Match and resolves a 304 to undefined', async () => {
    const api = mockApi({
      'GET /api/world/resources/x': () => new Response(null, { status: 304 }),
    });
    await expect(
      apiRequest('/api/world/resources/x', { ifNoneMatch: '"4"' }),
    ).resolves.toBeUndefined();
    expect(api.requests[0]?.headers.get('If-None-Match')).toBe('"4"');
  });
});

describe('api client', () => {
  it('sends the Bearer token and JSON headers, same-origin relative', async () => {
    const api = mockApi({ 'GET /api/auth/me': () => json(200, { ok: true }) });
    await apiRequest('/api/auth/me', { token: 'abc.def.ghi' });

    const [req] = api.requests;
    expect(req?.path).toBe('/api/auth/me');
    expect(req?.headers.get('Authorization')).toBe('Bearer abc.def.ghi');
    expect(req?.headers.get('Accept')).toContain('application/problem+json');
  });

  it('sends no Authorization header without a token, and JSON bodies as POST', async () => {
    const api = mockApi({ 'POST /api/auth/login': () => json(200, {}) });
    await apiRequest('/api/auth/login', { body: { username: 'a', password: 'b' } });

    const [req] = api.requests;
    expect(req?.headers.has('Authorization')).toBe(false);
    expect(req?.headers.get('Content-Type')).toBe('application/json');
    expect(req?.body).toEqual({ username: 'a', password: 'b' });
  });

  it('parses problem+json into an ApiError, including the errors extension', async () => {
    mockApi({
      'POST /api/auth/register': () =>
        problem(400, {
          title: 'Bad Request',
          detail: 'Request validation failed',
          errors: [{ field: 'username', message: 'is required' }, { junk: 1 }],
        }),
    });
    const err = await caught(apiRequest('/api/auth/register', { body: {} }));

    expect(err.kind).toBe('http');
    expect(err.status).toBe(400);
    expect(err.title).toBe('Bad Request');
    expect(err.detail).toBe('Request validation failed');
    expect(err.type).toBe('about:blank');
    expect(err.fieldErrors).toEqual([{ field: 'username', message: 'is required' }]);
    expect(errorMessage(err)).toBe('Request validation failed');
  });

  it('falls back to title, then a generic message', async () => {
    mockApi({
      'GET /a': () => problem(409, { title: 'Conflict' }),
      'GET /b': () =>
        new Response('<html>Bad gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
      'GET /c': () => new Response('{not json', { status: 500, headers: { 'Content-Type': 'application/problem+json' } }),
    });

    expect(errorMessage(await caught(apiRequest('/a')))).toBe('Conflict');
    const html = await caught(apiRequest('/b'));
    expect(html.status).toBe(502);
    expect(html.detail).toBeUndefined();
    expect(errorMessage(html)).toBe('Something went wrong (HTTP 502). Please try again.');
    expect(errorMessage(await caught(apiRequest('/c')))).toBe(
      'Something went wrong (HTTP 500). Please try again.',
    );
  });

  it('maps a 401 without detail to the given message', async () => {
    mockApi({ 'GET /x': () => new Response(null, { status: 401 }) });
    const err = await caught(apiRequest('/x'));
    expect(errorMessage(err, { unauthorized: 'Invalid username or password.' })).toBe(
      'Invalid username or password.',
    );
  });

  it('turns a network failure into a status-0 ApiError', async () => {
    mockApi({});
    const err = await caught(apiRequest('/api/auth/me'));
    expect(err.kind).toBe('network');
    expect(err.status).toBe(0);
    expect(errorMessage(err)).toBe(NETWORK_ERROR_MESSAGE);
  });

  it('rejects success bodies that are not JSON or do not match the contract', async () => {
    mockApi({
      'GET /api/auth/me': () => new Response('<html>', { status: 200 }),
      'POST /api/auth/login': () => json(200, { token: 'x' }),
    });
    expect((await caught(me('t'))).kind).toBe('invalid-response');
    expect((await caught(login({ username: 'a', password: 'b' }))).kind).toBe('invalid-response');
  });
});
