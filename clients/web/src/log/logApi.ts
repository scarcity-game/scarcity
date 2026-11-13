import type { Request } from '../resources/worldApi.ts';
import type { LogPage } from './types.ts';

export function fetchLogPage(request: Request, areaId: string, after: number, limit: number, signal?: AbortSignal, kind?: string): Promise<LogPage> {
  const kindParam = kind === undefined ? '' : '&kind=' + encodeURIComponent(kind);
  return request<LogPage>('/api/world/log?area=' + encodeURIComponent(areaId) + '&after=' + after + '&limit=' + limit + kindParam, { signal });
}
