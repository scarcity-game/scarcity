import type { Request } from '../resources/worldApi.ts';
import type { LogPage } from './types.ts';

/** Which page of an area's log to read: the entries after `after`, at most `limit`, optionally of one `kind`. */
export interface LogPageQuery {
  after: number;
  limit: number;
  kind?: string;
}

export function fetchLogPage(
  request: Request,
  areaId: string,
  { after, limit, kind }: LogPageQuery,
  signal?: AbortSignal,
): Promise<LogPage> {
  const kindParam = kind === undefined ? '' : '&kind=' + encodeURIComponent(kind);
  return request<LogPage>(
    '/api/world/log?area=' +
      encodeURIComponent(areaId) +
      '&after=' +
      after +
      '&limit=' +
      limit +
      kindParam,
    { signal },
  );
}
