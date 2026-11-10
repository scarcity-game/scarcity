import type { Attributes, AttributeValue, Context } from '@opentelemetry/api';
import type { ReadableSpan, Span, SpanProcessor } from '@opentelemetry/sdk-trace';

// Privacy filter for browser spans. Spans leave the browser over a public,
// unauthenticated endpoint, so only attributes on this allowlist are kept.
// Everything else (a future instrumentation's headers, bodies, user agent,
// user ids, ...) is dropped. Event attributes and status messages are dropped
// too: they can carry error text. Names and timestamps are kept.
const ALLOWED_ATTRIBUTES = new Set([
  'http.request.method',
  'http.request.method_original',
  'http.response.status_code',
  'error.type',
  'server.address',
  'server.port',
  'url.full', // reduced to origin + path, see scrubUrl
  'app.route', // a route pattern from RouteTracker
]);

const URL_ATTRIBUTES = new Set(['url.full']);

/**
 * Origin + path of a URL: drops the query string, the fragment and any
 * user:password. Relative URLs are resolved against the page. Anything
 * unparseable becomes "[invalid url]".
 */
export function scrubUrl(value: string): string {
  try {
    const url = new URL(value, globalThis.location?.href);
    return url.origin + url.pathname;
  } catch {
    return '[invalid url]';
  }
}

export function scrubAttributes(attributes: Attributes): Attributes {
  const clean: Attributes = {};
  for (const [key, value] of Object.entries(attributes)) {
    if (!ALLOWED_ATTRIBUTES.has(key) || value === undefined) continue;
    clean[key] = URL_ATTRIBUTES.has(key) && typeof value === 'string' ? scrubUrl(value) : value;
  }
  return clean;
}

// The SDK's span objects expose these as plain writable fields; the
// ReadableSpan type marks them readonly.
interface MutableSpan {
  name: string;
  attributes: Attributes;
  events: Array<{ name: string; time: unknown; attributes?: Record<string, AttributeValue> }>;
  status: { code: number; message?: string };
}

/** Maps a page pathname to its route pattern (`/play`, `/*`, ...). */
export type PageRoute = (pathname: string) => string;

const DOCUMENT_LOAD_SCOPE = '@opentelemetry/instrumentation-document-load';

/**
 * The page URL on documentLoad/documentFetch spans: origin + route pattern,
 * never the raw path, so a mistyped or crafted URL (or a future
 * `/profile/:name` route) can't put arbitrary text on a span.
 */
export function scrubPageUrl(value: string, pageRoute: PageRoute): string {
  try {
    const url = new URL(value, globalThis.location?.href);
    return url.origin + pageRoute(url.pathname);
  } catch {
    return '[invalid url]';
  }
}

/**
 * The URL on resourceFetch spans (everything the page loaded):
 * - same-origin `/assets/...`: kept (content-hashed build files);
 * - same-origin `/api/<service>/...`: cut to `/api/<service>/*`, since API
 *   paths may carry ids (the fetch spans of traced /api calls keep their
 *   path; these are mostly the requests made before the SDK loaded);
 * - anything else, cross-origin included: the origin only.
 */
export function scrubResourceUrl(value: string): string {
  try {
    const url = new URL(value, globalThis.location?.href);
    if (url.origin === globalThis.location?.origin) {
      if (url.pathname.startsWith('/assets/')) return url.origin + url.pathname;
      const api = /^\/api\/([^/]+)\//.exec(url.pathname + '/');
      if (api) return `${url.origin}/api/${api[1]}/*`;
    }
    return url.origin;
  } catch {
    return '[invalid url]';
  }
}

/** Rewrites a finished span in place so it only holds allowlisted data. */
export function scrubSpan(span: ReadableSpan, pageRoute: PageRoute = () => '/*'): void {
  const target = span as unknown as MutableSpan;
  // Span names here are fixed strings, methods or route patterns; cut a query
  // string anyway in case a future instrumentation names spans by URL.
  target.name = target.name.split(/[?#]/, 1)[0] ?? '';
  target.attributes = scrubAttributes(target.attributes);
  const url = target.attributes['url.full'];
  if (typeof url === 'string' && span.instrumentationScope.name === DOCUMENT_LOAD_SCOPE) {
    target.attributes['url.full'] =
      target.name === 'resourceFetch' ? scrubResourceUrl(url) : scrubPageUrl(url, pageRoute);
  }
  target.events = target.events.map((event) => ({ name: event.name, time: event.time }));
  target.status = { code: target.status.code };
}

/**
 * Wraps the real processor (batch, or simple in tests) and scrubs each span
 * before handing it on, so no exporter ever sees unscrubbed data.
 */
export class ScrubbingSpanProcessor implements SpanProcessor {
  private readonly next: SpanProcessor;
  private readonly pageRoute: PageRoute | undefined;

  constructor(next: SpanProcessor, pageRoute?: PageRoute) {
    this.next = next;
    this.pageRoute = pageRoute;
  }

  onStart(span: Span, parentContext: Context): void {
    this.next.onStart(span, parentContext);
  }

  onEnd(span: ReadableSpan): void {
    try {
      scrubSpan(span, this.pageRoute);
    } catch {
      return; // never export a span we could not scrub
    }
    this.next.onEnd(span);
  }

  forceFlush(): Promise<void> {
    return this.next.forceFlush();
  }

  shutdown(): Promise<void> {
    return this.next.shutdown();
  }
}
