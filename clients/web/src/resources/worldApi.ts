import type { RequestOptions } from '../api/client.ts';
import type { Resource } from './cache.ts';

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
): Promise<Resource[]> {
  const page = await request<Page<Resource>>(`${BASE}?type=${encodeURIComponent(type)}`, {
    signal,
  });
  return page.items;
}

/** First page (default size 50) of the children of one type. */
export async function listChildrenOfType(
  request: Request,
  parentId: string,
  type: string,
  signal?: AbortSignal,
): Promise<Resource[]> {
  const page = await request<Page<Resource>>(
    `${resourcePath(parentId)}/children?type=${encodeURIComponent(type)}`,
    { signal },
  );
  return page.items;
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
