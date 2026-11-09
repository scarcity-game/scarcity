import { vi } from 'vitest';
import { SESSION_STORAGE_KEY, type Session } from '../auth/sessionStore.ts';

export interface RecordedRequest {
  method: string;
  path: string;
  headers: Headers;
  body: unknown;
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>;

/**
 * Replaces global fetch with a router keyed by "METHOD /path". Returns the
 * list of requests seen. An unmocked request fails the fetch (and so shows up
 * as a network error in the UI) and is recorded in `unmatched`.
 */
export function mockApi(routes: Record<string, Handler | Handler[]>) {
  const requests: RecordedRequest[] = [];
  const unmatched: string[] = [];
  const queues = new Map<string, Handler[]>();
  for (const [key, value] of Object.entries(routes)) {
    queues.set(key, Array.isArray(value) ? [...value] : [value]);
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = new Headers(init.headers);
    const body = typeof init.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
    const request = { method, path, headers, body };
    requests.push(request);
    const key = `${method} ${path}`;
    const queue = queues.get(key);
    // A list of handlers is used in order; the last one repeats.
    const handler = queue && (queue.length > 1 ? queue.shift() : queue[0]);
    if (!handler) {
      unmatched.push(key);
      throw new TypeError(`Unmocked request: ${key}`);
    }
    if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    return handler(request);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { requests, unmatched, fetchMock };
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export function problem(status: number, fields: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ type: 'about:blank', status, ...fields }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

export const ALICE = {
  id: '6f1c2a9e-1111-4c2e-9a77-000000000001',
  username: 'alice',
  createdAt: '2026-11-09T10:15:30.123456Z',
};

export function inOneHour(): string {
  return new Date(Date.now() + 60 * 60 * 1000).toISOString();
}

export function tokenResponse(token = 'jwt-alice', expiresAt = inOneHour()) {
  return json(200, { token, tokenType: 'Bearer', expiresAt });
}

export function storeTestSession(session: Partial<Session> = {}): Session {
  const full: Session = {
    token: 'stored-jwt',
    expiresAt: inOneHour(),
    user: ALICE,
    ...session,
  };
  window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(full));
  return full;
}

export function readStoredSession(): Session | null {
  const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
  return raw === null ? null : (JSON.parse(raw) as Session);
}

/** A promise you resolve by hand, to hold a response in flight. */
export function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
