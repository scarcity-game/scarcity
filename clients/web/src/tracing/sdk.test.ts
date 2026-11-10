import { context, ROOT_CONTEXT, SpanStatusCode } from '@opentelemetry/api';
import { InMemorySpanExporter, StackContextManager, type ReadableSpan } from '@opentelemetry/sdk-trace';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, me } from '../api/auth.ts';
import { ALICE, json, mockApi, problem, tokenResponse } from '../test/mockApi.ts';
import type { TracingConfig } from './config.ts';
import { recordRouteChange, resetRouteEvents } from './routeEvents.ts';
import { initTracing, isTracedUrl, type TracingHandle } from './sdk.ts';

const ORIGIN = window.location.origin;
const CONFIG: TracingConfig = { mode: 'on', otlpUrl: '/otlp/v1/traces', serviceVersion: 'abc1234' };
const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-01$/;

let handle: TracingHandle | undefined;
let exporter: InMemorySpanExporter;

beforeEach(() => {
  exporter = new InMemorySpanExporter();
});

afterEach(async () => {
  await handle?.shutdown();
  handle = undefined;
  resetRouteEvents();
});

// Stub fetch first (mockApi), then start tracing: the fetch instrumentation
// wraps whatever globalThis.fetch is when it is enabled.
function startTracing(overrides: Parameters<typeof initTracing>[1] = { exporter, simpleProcessor: true }) {
  handle = initTracing(CONFIG, overrides);
  expect(handle).toBeDefined();
  return handle!;
}

// Fetch spans end ~300ms after the response (the instrumentation waits for
// resource timing entries).
async function finishedSpans(count: number): Promise<ReadableSpan[]> {
  await vi.waitFor(() => expect(exporter.getFinishedSpans()).toHaveLength(count), {
    timeout: 3000,
  });
  return exporter.getFinishedSpans();
}

describe('isTracedUrl', () => {
  it.each([
    [`${ORIGIN}/api`, true],
    [`${ORIGIN}/api/`, true],
    [`${ORIGIN}/api/auth/me`, true],
    [`${ORIGIN}/api?x=1`, true],
    [`${ORIGIN}/apiary`, false],
    [`${ORIGIN}/otlp/v1/traces`, false],
    [`${ORIGIN}/assets/index.js`, false],
    [`${ORIGIN}/`, false],
    ['https://example.com/api/x', false],
    [`${ORIGIN}.evil.example/api/x`, false],
  ])('%s -> %s', (url, expected) => {
    expect(isTracedUrl(url, ORIGIN)).toBe(expected);
  });
});

describe('fetch instrumentation', () => {
  it('adds a traceparent to same-origin /api requests, matching the exported span', async () => {
    const api = mockApi({ [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE) });
    startTracing();

    await me('jwt-alice');

    expect(api.requests).toHaveLength(1);
    const traceparent = api.requests[0]!.headers.get('traceparent');
    expect(traceparent).toMatch(TRACEPARENT);
    const [span] = await finishedSpans(1);
    const [, traceId, spanId] = TRACEPARENT.exec(traceparent!)!;
    expect(span!.spanContext().traceId).toBe(traceId);
    expect(span!.spanContext().spanId).toBe(spanId);
    // The fetch span is the root of the trace that the backend continues.
    expect(span!.parentSpanContext).toBeUndefined();
    expect(span!.name).toBe('GET');
    expect(span!.attributes['http.response.status_code']).toBe(200);
  });

  it('traces /api itself and /api with a query string', async () => {
    const api = mockApi({
      [`GET ${ORIGIN}/api`]: () => json(200, {}),
      [`GET ${ORIGIN}/api?probe=1`]: () => json(200, {}),
    });
    startTracing();

    await fetch('/api');
    await fetch('/api?probe=1');

    expect(api.requests.map((r) => r.headers.get('traceparent'))).toEqual([
      expect.stringMatching(TRACEPARENT),
      expect.stringMatching(TRACEPARENT),
    ]);
    await finishedSpans(2);
  });

  it('does not trace or add headers to cross-origin, /otlp, /apiary or asset requests', async () => {
    const api = mockApi({
      'GET https://example.com/api/x': () => json(200, {}),
      'POST /otlp/v1/traces': () => json(200, {}),
      'GET /apiary': () => json(200, {}),
      'GET /assets/app.js': () => json(200, {}),
    });
    startTracing();

    await fetch('https://example.com/api/x');
    await fetch('/otlp/v1/traces', { method: 'POST', body: '{}' });
    await fetch('/apiary');
    await fetch('/assets/app.js');

    expect(api.unmatched).toEqual([]);
    for (const request of api.requests) {
      expect(request.headers.get('traceparent')).toBeNull();
      expect(request.headers.get('tracestate')).toBeNull();
    }
    // Give the instrumentation the time it would need to end a span.
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(exporter.getFinishedSpans()).toEqual([]);
  });

  it('keeps the app working when a traced request fails', async () => {
    mockApi({}); // every request is a network error
    startTracing();

    await expect(me('jwt')).rejects.toMatchObject({ kind: 'network' });
    const [span] = await finishedSpans(1);
    expect(span!.status).toEqual({ code: SpanStatusCode.ERROR });
    expect(span!.attributes['error.type']).toBe('TypeError');
  });

  it('does not mark an aborted request as an error', async () => {
    mockApi({ [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE) });
    startTracing();
    const controller = new AbortController();
    controller.abort();

    await expect(me('jwt', controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    const [span] = await finishedSpans(1);
    expect(span!.status.code).toBe(SpanStatusCode.UNSET);
    expect(span!.attributes['error.type']).toBeUndefined();
  });

  it('stops tracing after shutdown', async () => {
    const api = mockApi({ [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE) });
    await startTracing().shutdown();
    handle = undefined;
    api.fetchMock.mockClear();

    await fetch(`${ORIGIN}/api/auth/me`);

    expect(api.requests.at(-1)!.headers.get('traceparent')).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(exporter.getFinishedSpans()).toEqual([]);
  });
});

describe('privacy', () => {
  it('exports no passwords, tokens, usernames, bodies or query strings', async () => {
    const PASSWORD = 'correct-horse-battery-staple';
    const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.secret-payload.signature';
    const QUERY_SECRET = 'query-secret-value';
    mockApi({
      [`POST ${ORIGIN}/api/auth/login`]: () => tokenResponse(TOKEN),
      [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE),
      [`GET ${ORIGIN}/api/auth/me?next=%2Fplay&key=${QUERY_SECRET}#frag-${QUERY_SECRET}`]: () =>
        problem(500, { detail: `failed for ${ALICE.username}` }),
    });
    startTracing();

    const { token } = await login({ username: ALICE.username, password: PASSWORD });
    await me(token);
    await fetch(`/api/auth/me?next=%2Fplay&key=${QUERY_SECRET}#frag-${QUERY_SECRET}`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    recordRouteChange('/*', 'route-key-1');

    const spans = await finishedSpans(4);
    const exported = JSON.stringify(spans.map(describeSpan));
    for (const secret of [PASSWORD, TOKEN, 'secret-payload', 'Bearer', ALICE.username, ALICE.id, QUERY_SECRET, 'next=', '?', '#']) {
      expect(exported).not.toContain(secret);
    }
    const urls = spans.map((span) => span.attributes['url.full']).filter(Boolean);
    expect(urls).toEqual(
      expect.arrayContaining([`${ORIGIN}/api/auth/login`, `${ORIGIN}/api/auth/me`]),
    );
    expect(spans.some((span) => span.attributes['http.response.status_code'] === 500)).toBe(true);
  });

  it('keeps only allowlisted attributes and no user agent', async () => {
    mockApi({ [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE) });
    startTracing();

    await me('jwt-alice');

    const [span] = await finishedSpans(1);
    expect(Object.keys(span!.attributes).sort()).toEqual(
      expect.arrayContaining(['http.request.method', 'http.response.status_code', 'url.full']),
    );
    for (const key of Object.keys(span!.attributes)) {
      expect(key).not.toMatch(/user_agent|header|body|user\.|enduser/);
    }
  });
});

describe('resource and route spans', () => {
  it('sets service.name, service.version and k8s.namespace.name', async () => {
    startTracing();
    recordRouteChange('/play', 'route-key-2');

    const [span] = await finishedSpans(1);
    expect(span!.resource.attributes).toMatchObject({
      'service.name': 'client-web',
      'service.version': 'abc1234',
      'k8s.namespace.name': 'scarcity',
    });
  });

  it('replays route changes recorded before the SDK loaded, with their original time', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.UTC(2026, 10, 9, 12, 0, 0));
    recordRouteChange('/', 'route-key-3');
    vi.mocked(Date.now).mockRestore();

    startTracing();
    recordRouteChange('/login', 'route-key-4');
    recordRouteChange('/login', 'route-key-4'); // StrictMode re-run: ignored

    const spans = await finishedSpans(2);
    expect(spans.map((span) => [span.name, span.attributes['app.route']])).toEqual([
      ['navigate /', '/'],
      ['navigate /login', '/login'],
    ]);
    expect(spans[0]!.startTime[0]).toBe(Date.UTC(2026, 10, 9, 12, 0, 0) / 1000);
    expect(spans[0]!.parentSpanContext).toBeUndefined();
  });
});

describe('document load spans', () => {
  // The document-load instrumentation reads the Performance API, which jsdom
  // lacks, so emit spans with its scope and attributes directly.
  it('replace the page path with the route pattern and cut /api resource paths', async () => {
    const tracing = startTracing();
    const tracer = tracing.provider.getTracer('@opentelemetry/instrumentation-document-load');
    const emit = (name: string, url: string) =>
      tracer.startSpan(name, { attributes: { 'url.full': url } }, ROOT_CONTEXT).end();

    emit('documentLoad', `${ORIGIN}/profile/alice-secret?next=%2Fplay`);
    emit('documentFetch', `${ORIGIN}/play?token=abc`);
    emit('resourceFetch', `${ORIGIN}/assets/index-AbC123.js`);
    emit('resourceFetch', `${ORIGIN}/api/auth/me`);
    emit('resourceFetch', `${ORIGIN}/api/users/6f1c2a9e-1111-4c2e/inventory`);
    emit('resourceFetch', 'https://cdn.example.com/lib/alice-secret.js');

    const spans = await finishedSpans(6);
    expect(spans.map((span) => [span.name, span.attributes['url.full']])).toEqual([
      ['documentLoad', `${ORIGIN}/*`],
      ['documentFetch', `${ORIGIN}/play`],
      ['resourceFetch', `${ORIGIN}/assets/index-AbC123.js`],
      ['resourceFetch', `${ORIGIN}/api/auth/*`],
      ['resourceFetch', `${ORIGIN}/api/users/*`],
      ['resourceFetch', 'https://cdn.example.com'],
    ]);
    expect(JSON.stringify(spans.map(describeSpan))).not.toMatch(/alice|6f1c2a9e|token|next/);
  });

  it('leaves url.full of fetch spans to the generic scrubbing', async () => {
    const tracing = startTracing();
    tracing.provider
      .getTracer('@opentelemetry/instrumentation-fetch')
      .startSpan('GET', { attributes: { 'url.full': `${ORIGIN}/api/auth/me?x=1` } }, ROOT_CONTEXT)
      .end();

    const [span] = await finishedSpans(1);
    expect(span!.attributes['url.full']).toBe(`${ORIGIN}/api/auth/me`);
  });
});

describe('OTLP export', () => {
  it('posts JSON to /otlp/v1/traces without tracing its own request', async () => {
    const api = mockApi({
      [`GET ${ORIGIN}/api/auth/me`]: () => json(200, ALICE),
      [`POST ${ORIGIN}/otlp/v1/traces`]: () => json(200, {}),
    });
    const tracing = startTracing({}); // the real BatchSpanProcessor + OTLP exporter

    await me('jwt-alice');
    await vi.waitFor(async () => {
      await tracing.provider.forceFlush();
      expect(api.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    }, { timeout: 3000 });
    await new Promise((resolve) => setTimeout(resolve, 400));
    await tracing.provider.forceFlush();

    const posts = api.requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1); // no span for the export, so nothing more to send
    expect(posts[0]!.path).toBe(`${ORIGIN}/otlp/v1/traces`);
    expect(posts[0]!.headers.get('content-type')).toBe('application/json');
    expect(posts[0]!.headers.get('traceparent')).toBeNull();
    expect(posts[0]!.headers.get('authorization')).toBeNull();
    const postInit = api.fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')![1]!;
    const body = JSON.parse(new TextDecoder().decode(postInit.body as Uint8Array)) as {
      resourceSpans: Array<{ scopeSpans: Array<{ spans: unknown[] }> }>;
    };
    expect(body.resourceSpans[0]!.scopeSpans[0]!.spans).toHaveLength(1);
  });

  it('sends one request on a 429 with Retry-After, then pauses exports without unhandled rejections', async () => {
    const tooMany = () =>
      new Response('rate limited', { status: 429, headers: { 'Retry-After': '60' } });
    const api = mockApi({ [`POST ${ORIGIN}/otlp/v1/traces`]: tooMany });
    const tracing = startTracing({});
    const tracer = tracing.provider.getTracer('test');

    tracer.startSpan('first', {}, ROOT_CONTEXT).end();
    hideDocument(); // the processor's own flush path, which must swallow failures
    await vi.waitFor(() => expect(api.requests).toHaveLength(1), { timeout: 3000 });
    await new Promise((resolve) => setTimeout(resolve, 100));

    tracer.startSpan('second', {}, ROOT_CONTEXT).end();
    await expect(tracing.provider.forceFlush()).rejects.toThrow();
    expect(api.requests).toHaveLength(1);
  });
});

describe('failure isolation', () => {
  it('returns undefined and leaves fetch alone when init fails', async () => {
    const existing = new StackContextManager().enable();
    context.setGlobalContextManager(existing); // makes initTracing's registration fail
    try {
      const api = mockApi({ [`GET /api/auth/me`]: () => json(200, ALICE) });
      const stub = globalThis.fetch;

      expect(initTracing(CONFIG, { exporter, simpleProcessor: true })).toBeUndefined();

      expect(globalThis.fetch).toBe(stub);
      await me('jwt-alice');
      expect(api.requests[0]!.headers.get('traceparent')).toBeNull();
    } finally {
      context.disable();
    }
    // And a later init still works.
    startTracing();
  });

  it('returns the active handle when started twice', () => {
    const first = startTracing();
    expect(initTracing(CONFIG, { exporter })).toBe(first);
  });
});

function hideDocument() {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
}

function describeSpan(span: ReadableSpan) {
  return {
    name: span.name,
    attributes: span.attributes,
    events: span.events,
    status: span.status,
    links: span.links,
    resource: span.resource.attributes,
  };
}
