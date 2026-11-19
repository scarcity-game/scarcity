// A mocked backend for the browser: REST by route interception, the
// subscriptions WebSocket by routeWebSocket, a session in sessionStorage.
import type { BrowserContext, Page, Route } from '@playwright/test';
import { area, entities, game, log, USER } from './fixtures.ts';

interface Reply {
  status: number;
  body: unknown;
}

const page = (items: unknown[]): Reply => ({ status: 200, body: { items, page: 0, size: 200, total: items.length } });
const ok = (body: unknown): Reply => ({ status: 200, body });

function logReply(params: URLSearchParams): Reply {
  const after = Number(params.get('after') ?? 0);
  const limit = Number(params.get('limit') ?? 500);
  const kind = params.get('kind');
  const pool = log.filter((e) => e.seq > after && (kind === null || e.kind === kind));
  return ok({ entries: pool.slice(0, limit), first: log[0]!.seq, last: log[log.length - 1]!.seq });
}

function resourceReply(path: string): Reply | null {
  if (path === '/api/world/resources') return page([game]);
  const id = decodeURIComponent(path.slice('/api/world/resources/'.length));
  if (id === game.id) return ok(game);
  if (id === area.id) return ok(area);
  if (id === `${game.id}/children`) return page([area]);
  if (id === `${area.id}/children`) return page(entities);
  return null;
}

/** The reply to a request, or null for a path the mock does not know. */
export function reply(url: URL): Reply | null {
  const { pathname, searchParams } = url;
  if (pathname === '/api/auth/me') return ok(USER);
  if (pathname === '/api/world/log') return logReply(searchParams);
  if (pathname.startsWith('/api/world/resources')) return resourceReply(pathname);
  return null;
}

async function fulfil(route: Route, unmatched: string[]): Promise<void> {
  const url = new URL(route.request().url());
  const found = reply(url);
  if (found === null) {
    unmatched.push(`${route.request().method()} ${url.pathname}${url.search}`);
    await route.fulfill({ status: 404, contentType: 'application/problem+json', body: '{"status":404}' });
    return;
  }
  await route.fulfill({ status: found.status, contentType: 'application/json', body: JSON.stringify(found.body) });
}

function session(): string {
  const expiresAt = new Date(Date.now() + 3_600_000).toISOString();
  return JSON.stringify({ token: 'screenshot-token', expiresAt, user: USER });
}

function onSocketFrame(data: string | Buffer, send: (frame: unknown) => void): void {
  const frame = JSON.parse(String(data)) as { type: string; area?: string };
  if (frame.type === 'auth') send({ type: 'ready', user: USER.id, username: USER.username });
  if (frame.type === 'subscribe') send({ type: 'subscribed', area: frame.area });
}

/** Mocks REST under /api/, the session and the subscriptions socket. Returns the requests it could not answer. */
export async function mockBackend(context: BrowserContext, target: Page): Promise<string[]> {
  const unmatched: string[] = [];
  await context.addInitScript({ content: `sessionStorage.setItem('scarcity.session', ${JSON.stringify(session())});` });
  await context.route((url) => url.pathname.startsWith('/api/'), (route) => fulfil(route, unmatched));
  await target.routeWebSocket(/\/api\/subscriptions\/ws/, (socket) => {
    socket.onMessage((data) => onSocketFrame(data, (frame) => socket.send(JSON.stringify(frame))));
  });
  return unmatched;
}
