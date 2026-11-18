// The OpenTelemetry browser SDK setup. This module (and everything it
// imports) is a separate chunk, loaded with a dynamic import() after React has
// mounted; see start.ts and main.tsx.
//
// - Fetch spans only for same-origin /api requests. Only those get a W3C
//   `traceparent` header, so a user action's fetch span is the root of a trace
//   that the backend (auth, ...) continues.
// - Document load spans, recorded after the fact from the Performance API.
// - Route change spans, from RouteTracker via routeEvents.ts.
// - Every span is scrubbed (scrub.ts) before it reaches the exporter.
// - Batched OTLP/HTTP JSON export to /otlp/v1/traces, with a cooldown after a
//   failure (exporter.ts).
//
// Context propagation: StackContextManager, not ZoneContextManager (too heavy).
// Context only follows synchronous calls: a span is not automatically the
// parent of work done after an `await`. Each /api fetch is therefore the root
// of its own trace unless the caller wraps the fetch call in context.with().

import {
  context,
  propagation,
  ROOT_CONTEXT,
  SpanKind,
  SpanStatusCode,
  trace,
  type Span,
} from '@opentelemetry/api';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { defaultResource, resourceFromAttributes } from '@opentelemetry/resources';
import {
  BatchSpanProcessor,
  ConsoleSpanExporter,
  SimpleSpanProcessor,
  StackContextManager,
  TracerProvider,
  type SpanExporter,
  type SpanProcessor,
} from '@opentelemetry/sdk-trace';
import type { TracingConfig } from './config.ts';
import { CooldownExporter } from './exporter.ts';
import { onRouteChange } from './routeEvents.ts';
import { routePattern } from './RouteTracker.tsx';
import { ScrubbingSpanProcessor } from './scrub.ts';

export const SERVICE_NAME = 'client-web';
export const K8S_NAMESPACE = 'scarcity';

// Batching, sized for the shared 600 requests/min limit on /otlp: an active
// client sends at most one request per 10s (more only in a burst of over 128
// spans), and an idle one sends none. The queue is bounded; overflow is dropped.
export const BATCH_OPTIONS = {
  scheduledDelayMillis: 10_000,
  maxExportBatchSize: 128,
  maxQueueSize: 1024,
  exportTimeoutMillis: 15_000,
} as const;

// The exporter retries 429/502/503/504 and network errors itself (backoff
// from 1s, x1.5, honouring Retry-After) but only within its timeout; a short
// timeout keeps that to a few attempts. CooldownExporter then pauses exports.
export const OTLP_TIMEOUT_MS = 4000;

export interface TracingOverrides {
  /** Replaces the OTLP (or console) exporter, e.g. an InMemorySpanExporter. */
  exporter?: SpanExporter;
  /** Export each span as it ends instead of batching (tests). */
  simpleProcessor?: boolean;
}

export interface TracingHandle {
  readonly provider: TracerProvider;
  /** Stops instrumenting, flushes and unregisters the global OTel state. */
  shutdown(): Promise<void>;
}

let active: TracingHandle | undefined;

/**
 * Same-origin `/api` or `/api/...` (whole path segment). Exported for tests.
 * Anything else (cross-origin, /otlp, /apiary, assets) is not traced at all.
 */
export function isTracedUrl(url: string, origin: string): boolean {
  return new RegExp(`^${tracedUrlPattern(origin)}`).test(url);
}

function tracedUrlPattern(origin: string): string {
  return `${escapeRegExp(origin)}/api(?:[/?#]|$)`;
}

function createExporter(config: TracingConfig, overrides: TracingOverrides): SpanExporter {
  if (overrides.exporter) return overrides.exporter;
  if (config.mode === 'console') return new ConsoleSpanExporter();
  return new CooldownExporter(
    new OTLPTraceExporter({
      url: config.otlpUrl,
      timeoutMillis: OTLP_TIMEOUT_MS,
      concurrencyLimit: 2,
    }),
  );
}

function createProvider(config: TracingConfig, overrides: TracingOverrides): TracerProvider {
  const exporter = createExporter(config, overrides);
  const inner: SpanProcessor = overrides.simpleProcessor
    ? new SimpleSpanProcessor({ exporter })
    : new BatchSpanProcessor({ exporter, ...BATCH_OPTIONS });
  return new TracerProvider({
    resource: defaultResource().merge(
      resourceFromAttributes({
        'service.name': SERVICE_NAME,
        'service.version': config.serviceVersion,
        'k8s.namespace.name': K8S_NAMESPACE,
      }),
    ),
    spanProcessors: [new ScrubbingSpanProcessor(inner, routePattern)],
  });
}

// Registers the global context manager, propagator and tracer provider; each
// undo is pushed as it succeeds. Throws if one is already registered.
function registerGlobals(tracerProvider: TracerProvider, undo: Array<() => void>): void {
  if (!context.setGlobalContextManager(new StackContextManager().enable())) {
    throw new Error('an OpenTelemetry context manager is already registered');
  }
  undo.push(() => context.disable());
  if (!propagation.setGlobalPropagator(new W3CTraceContextPropagator())) {
    throw new Error('an OpenTelemetry propagator is already registered');
  }
  undo.push(() => propagation.disable());
  if (!trace.setGlobalTracerProvider(tracerProvider)) {
    throw new Error('an OpenTelemetry tracer provider is already registered');
  }
  undo.push(() => trace.disable());
}

function instrumentFetch(tracerProvider: TracerProvider, undo: Array<() => void>): void {
  const origin = globalThis.location.origin;
  const fetchInstrumentation = new FetchInstrumentation({
    enabled: false,
    // Only same-origin /api requests get a span (and so a traceparent). The
    // exporter's own POSTs to /otlp are excluded here too, besides being
    // sent through the unpatched fetch with tracing suppressed.
    ignoreUrls: [new RegExp(`^(?!${tracedUrlPattern(origin)})`)],
    // Never send traceparent to another origin.
    propagateTraceHeaderCorsUrls: [],
    measureRequestSize: false,
    applyCustomAttributesOnSpan: markFetchError,
  });
  fetchInstrumentation.setTracerProvider(tracerProvider);
  fetchInstrumentation.enable();
  undo.push(() => fetchInstrumentation.disable());
}

function instrumentDocumentLoad(tracerProvider: TracerProvider, undo: Array<() => void>): void {
  const documentLoad = new DocumentLoadInstrumentation({ enabled: false });
  documentLoad.setTracerProvider(tracerProvider);
  documentLoad.enable();
  undo.push(() => documentLoad.disable());
}

function traceNavigation(
  tracerProvider: TracerProvider,
  config: TracingConfig,
  undo: Array<() => void>,
): void {
  const tracer = tracerProvider.getTracer(SERVICE_NAME, config.serviceVersion);
  undo.push(
    onRouteChange(({ route, time }) => {
      tracer
        .startSpan(
          `navigate ${route}`,
          { kind: SpanKind.INTERNAL, startTime: time, attributes: { 'app.route': route } },
          ROOT_CONTEXT,
        )
        .end(time);
    }),
  );
}

function createHandle(tracerProvider: TracerProvider, undo: Array<() => void>): TracingHandle {
  const handle: TracingHandle = {
    provider: tracerProvider,
    async shutdown() {
      if (active === handle) active = undefined;
      runUndo(undo);
      await tracerProvider.shutdown().catch(() => undefined);
    },
  };
  return handle;
}

/**
 * Starts tracing. Returns undefined (and leaves fetch and the global OTel
 * state as they were) if anything fails. Calling it again while tracing is
 * active returns the active handle.
 */
export function initTracing(
  config: TracingConfig,
  overrides: TracingOverrides = {},
): TracingHandle | undefined {
  if (active) return active;
  const undo: Array<() => void> = [];
  let provider: TracerProvider | undefined;
  try {
    const tracerProvider = createProvider(config, overrides);
    provider = tracerProvider;
    registerGlobals(tracerProvider, undo);
    instrumentFetch(tracerProvider, undo);
    instrumentDocumentLoad(tracerProvider, undo);
    traceNavigation(tracerProvider, config, undo);
    active = createHandle(tracerProvider, undo);
    return active;
  } catch {
    runUndo(undo);
    void provider?.shutdown().catch(() => undefined);
    return undefined;
  }
}

/**
 * The instrumentation leaves a fetch that rejects (network error, CORS) with
 * an unset status. Mark it as an error with the exception's name as
 * `error.type` (never its message). An abort is not an error: the app aborts
 * superseded requests on purpose.
 */
function markFetchError(span: Span, _request: unknown, result: unknown): void {
  if (result instanceof Response) return;
  const name = (result as { name?: unknown } | null)?.name;
  if (name === 'AbortError') return;
  span.setStatus({ code: SpanStatusCode.ERROR });
  span.setAttribute(
    'error.type',
    typeof name === 'string' && /^[A-Za-z]{1,40}$/.test(name) ? name : 'fetch_error',
  );
}

function runUndo(undo: Array<() => void>): void {
  for (const step of undo.splice(0).reverse()) {
    try {
      step();
    } catch {
      // keep undoing the rest
    }
  }
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
