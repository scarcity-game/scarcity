import type { RequestOptions } from '../api/client.ts';
import type { ListedResource, Resource } from './cache.ts';

/** `authRequest` from useAuth(). */
export type Request = <T>(path: string, options?: Omit<RequestOptions, 'token'>) => Promise<T>;

interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

const BASE = '/api/world/resources';

// Resource ids contain ':' ("area:7f3c..."); encode the path segment.
export const resourcePath = (id: string): string => `${BASE}/${encodeURIComponent(id)}`;

/** First page (default size 50) of the resources of a type. */
export async function listResources(
  request: Request,
  type: string,
  signal?: AbortSignal,
): Promise<ListedResource[]> {
  const page = await request<Page<ListedResource>>(`${BASE}?type=${encodeURIComponent(type)}`, {
    signal,
  });
  return page.items;
}

/** The first entity of a kind (`player`, `npc`…) anywhere in a game: one request, whatever the depth. */
export async function findGameEntity(
  request: Request,
  gameId: string,
  entityKind: string,
  signal?: AbortSignal,
): Promise<Resource | undefined> {
  const query = `type=entity&game=${encodeURIComponent(gameId)}&entityKind=${encodeURIComponent(entityKind)}&size=1`;
  const page = await request<Page<Resource>>(`${BASE}?${query}`, { signal });
  return page.items[0];
}

/** The area of a game that holds `childId` among its children; undefined if none does. */
export async function findAreaHolding(
  request: Request,
  gameId: string,
  childId: string,
  signal?: AbortSignal,
): Promise<ListedResource | undefined> {
  const query = `type=area&game=${encodeURIComponent(gameId)}&child=${encodeURIComponent(childId)}&size=1`;
  const page = await request<Page<ListedResource>>(`${BASE}?${query}`, { signal });
  return page.items[0];
}

/** All pages of a resource's children (size 200 per page). */
export async function listAllChildren(
  request: Request,
  parentId: string,
  signal?: AbortSignal,
): Promise<Resource[]> {
  const out: Resource[] = [];
  for (let page = 0; ; page++) {
    const result = await request<Page<Resource>>(
      `${resourcePath(parentId)}/children?size=200&page=${page}`,
      { signal },
    );
    out.push(...result.items);
    if (result.items.length === 0 || out.length >= result.total) return out;
  }
}

/** GET one resource. With `cachedVersion`, a 304 resolves to undefined (keep what you have). */
export function getResource(
  request: Request,
  id: string,
  options: { cachedVersion?: number; signal?: AbortSignal } = {},
): Promise<Resource | undefined> {
  return request<Resource | undefined>(resourcePath(id), {
    signal: options.signal,
    ifNoneMatch: options.cachedVersion === undefined ? undefined : `"${options.cachedVersion}"`,
  });
}

/** The body of `GET …/{id}/versions` (docs/resources.md): what is under a resource, at which versions. */
export interface Versions {
  id: string;
  versions: Record<string, number>;
  checksum: string;
  logSeq?: number;
}

/** The versions of `id` and everything under it, their checksum and the area's log head. */
export function getVersions(request: Request, id: string, signal?: AbortSignal): Promise<Versions> {
  return request<Versions>(`${resourcePath(id)}/versions`, { signal });
}

export const AREA_PAGE_SIZE = 50;

export interface ResourcePage {
  items: ListedResource[];
  total: number;
}

/** One page of a game's areas, from the server; `activeOnly` keeps those with an `activeEntity`. */
export async function listGameAreas(
  request: Request,
  gameId: string,
  options: { activeOnly: boolean; page: number; signal?: AbortSignal },
): Promise<ResourcePage> {
  const has = options.activeOnly ? '&has=activeEntity' : '';
  const query = `type=area&game=${encodeURIComponent(gameId)}${has}&size=${AREA_PAGE_SIZE}&page=${options.page}`;
  const page = await request<Page<ListedResource>>(`${BASE}?${query}`, {
    signal: options.signal,
  });
  return { items: page.items, total: page.total };
}
