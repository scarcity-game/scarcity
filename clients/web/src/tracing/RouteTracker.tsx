import { useEffect } from 'react';
import { matchRoutes, useLocation } from 'react-router';
import { routes } from '../routes.tsx';
import { recordRouteChange } from './routeEvents.ts';

/**
 * The route pattern for a pathname, e.g. `/play`, `/` or `/*` for an unknown
 * path. Only the pattern is traced, never the pathname or query string, so a
 * mistyped or crafted URL can't put arbitrary text on a span.
 */
export function routePattern(pathname: string): string {
  const matches = matchRoutes(routes, pathname) ?? [];
  const parts = matches
    .map((match) => match.route.path)
    .filter((path): path is string => path !== undefined && path !== '')
    .map((path) => path.replace(/^\/+|\/+$/g, ''));
  return '/' + parts.filter((part) => part !== '').join('/');
}

/** Records every committed route change for tracing (see routeEvents.ts). */
export function RouteTracker() {
  const { pathname, key } = useLocation();
  useEffect(() => {
    recordRouteChange(routePattern(pathname), key);
  }, [pathname, key]);
  return null;
}
