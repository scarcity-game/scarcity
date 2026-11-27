// A mocked backend for the browser: REST by route interception, the
// subscriptions WebSocket by routeWebSocket, a session in sessionStorage.
import type { BrowserContext, Page, Route } from '@playwright/test';
import { area, arenaOther, arenaOtherEntities, arenaYours, arenaYoursEntities, DIVE, dive, diveCells, diveEntities, entities, roomCells, game, log, SOLO_GAME, soloGame, USER } from './fixtures.ts';

const profiles = {
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

const diver = { entity: 'entity:00000000-0000-4000-8000-000000000015', controller: { kind: 'service', principal: 'diver' }, since: 40 };
const moreAreas = [2, 3, 4, 5].map((n) => ({
  ...area,
  id: `area:00000000-0000-4000-8000-0000000001${n}0`,
  name: `Depth ${n} cellar`,
  depth: n,
  time: 40 * n,
  children: area.children.slice(0, n),
  ...(n === 3 ? { activeEntity: diver } : {}),
}));

type Held = { children: string[] };

/** A game or an area as a listing gives it: how many children of each kind, not their ids. */
function listed<T extends Held>({ children, ...rest }: T): Omit<T, 'children'> & { childCounts: Record<string, number> } {
  const childCounts: Record<string, number> = {};
  for (const id of children) {
    const kind = id.slice(0, id.indexOf(':'));
    childCounts[kind] = (childCounts[kind] ?? 0) + 1;
  }
  return { ...rest, childCounts };
}

const listedPage = (items: Held[]): Reply => ok({ items: items.map(listed), page: 0, size: 50, total: items.length });

/** The game's areas: the first is active too, so "Active" shows two of five. */
function areaList(params: URLSearchParams): Reply {
  const child = params.get('child');
  if (child !== null) return listedPage([area, ...moreAreas, dive, arenaYours, arenaOther].filter((a) => a.children.includes(child)).slice(0, 1));
  if (params.get('game') === SOLO_GAME) return listedPage([area]);
  const all = [{ ...area, activeEntity: { ...diver, entity: area.children[0] } }, ...moreAreas];
  return listedPage(params.getAll('has').includes('activeEntity') ? all.filter((a) => 'activeEntity' in a) : all);
}

function soloReply(id: string): Reply | null {
  if (id === game.id) return ok(game);
  if (id === SOLO_GAME) return ok(soloGame);
  return id === `${SOLO_GAME}/children` ? listedPage([area]) : null;
}

/** The dive level and its entities. */
function diveReply(id: string): Reply | null {
  if (id === area.id) return ok(area);
  if (id === `${area.id}/children`) return page([...entities, ...roomCells]);
  if (id === dive.id) return ok(dive);
  return id === `${DIVE}/children` ? page([...diveEntities, ...diveCells]) : null;
}

/** The arena areas: one where it is the user's turn, one where it is not. */
function arenaReply(id: string): Reply | null {
  for (const [a, entities] of [[arenaYours, arenaYoursEntities], [arenaOther, arenaOtherEntities]] as const) {
    if (id === a.id) return ok(a);
    if (id === `${a.id}/children`) return page(entities);
  }
  return null;
}

function resourceReply(path: string, params: URLSearchParams): Reply | null {
  if (path === '/api/world/resources' && params.get('type') === 'area') return areaList(params);
  if (path === '/api/world/resources') return listedPage([game]);
  const id = decodeURIComponent(path.slice('/api/world/resources/'.length));
  const solo = soloReply(id) ?? diveReply(id) ?? arenaReply(id);
  if (solo) return solo;
  if (id === `${game.id}/children`) return listedPage([area, ...moreAreas, dive]);
  return null;
}

/** The reply to a request, or null for a path the mock does not know. */
export function reply(url: URL, method = 'GET'): Reply | null {
  const { pathname, searchParams } = url;
  if (method === 'POST' && pathname === '/api/world/intents') {
    return { status: 409, body: { error: 'the entity changed since the intent was made (ifVersion)' } };
  }
  if (pathname === '/api/auth/me') return ok(USER);
  if (pathname === '/api/world/games/profiles') return ok(profiles);
  if (pathname === '/api/world/log') return logReply(searchParams);
  if (pathname.startsWith('/api/world/resources')) return resourceReply(pathname, searchParams);
  return null;
}

async function fulfil(route: Route, unmatched: string[]): Promise<void> {
  const url = new URL(route.request().url());
  const found = reply(url, route.request().method());
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
