# clients/web: browser tracing (task #18, 2026-11-09)

Read when changing `src/tracing/`, the OTel dependencies or what the browser sends to `/otlp`. Moved verbatim from the decision log in `AGENTS.md`.

  - **Goal.** A user action's `/api` fetch starts a trace and its W3C
    `traceparent` goes to the backend. Spans go to the same-origin
    `/otlp/v1/traces` (Gateway -> Alloy, 600 req/min shared by all clients).
  - **Code** is in `src/tracing/`:
    - `start.ts` (`startTracing`, never throws or rejects) and `config.ts`;
    - `routeEvents.ts` plus `RouteTracker.tsx` (route spans; the buffer
      lives in the main bundle and has no OTel imports);
    - `sdk.ts` (the lazy chunk, `initTracing`), `scrub.ts`
      (`ScrubbingSpanProcessor`), `exporter.ts` (`CooldownExporter`).
    - `main.tsx` mounts React first, then does a dynamic `import()` of the
      SDK behind a literal `__SCARCITY_TRACING__ !== 'off'`. Keep the literal
      in `main.tsx`: through a re-exported constant Rolldown still emits the
      chunk.
  - **SDK line.** OTel JS 3.x (`sdk-trace` replaces `sdk-trace-base` and
    `sdk-trace-web`; no `register()`, so globals are set by hand), not 2.x,
    because instrumentation 0.301 depends on core 3. Versions are pinned
    exactly. They were repinned to the 2026-11-05 releases (3.1.0 / 0.301.0),
    avoiding same-day releases, and the `overrides` block in `package.json`
    keeps transitive deps on them. When bumping, check publish dates and
    drop or update the overrides together.
    `FetchInstrumentation` is deprecated upstream in favour of
    `@opentelemetry/browser-instrumentation` (experimental); moving is a
    follow-up.
  - **Exporter.** OTLP/HTTP JSON, chosen over protobuf by measurement (the
    sdk chunk is about 1.6 kB gzip smaller). `BatchSpanProcessor`: 10 s delay,
    128 per batch, queue 1024 (overflow dropped), export timeout 15 s. Flushes
    on `visibilitychange` to hidden. The OTLP exporter retries 429/502/503/504
    by default; we cap `timeoutMillis` at 4 s (about 3 attempts, none with a
    `Retry-After` over 4 s), and `CooldownExporter` drops all exports for 60 s
    after any failed one. Idle clients send nothing.
  - **What is traced.** Spans exist only for same-origin `/api` and `/api/...`
    (whole segment) fetches, via `ignoreUrls` for everything else;
    `propagateTraceHeaderCorsUrls: []` is a second guard against cross-origin
    propagation. So `/otlp`, `/apiary`, assets and cross-origin get no span and
    no header. The exporter uses the unpatched `fetch.__original` inside
    `suppressTracing`, so there is no feedback loop. Also: document-load spans,
    and `navigate <route pattern>` spans on route change.
  - **Privacy.** `ScrubbingSpanProcessor` keeps an attribute allowlist only
    (`http.request.method`, `http.response.status_code`, `error.type`,
    `server.address/port`, `url.full`, `app.route`), drops events and status
    messages and cuts `?`/`#` from names. `url.full` is origin + path for fetch
    spans. For documentLoad/documentFetch it is origin + route pattern (unknown
    paths become `/*`). For resourceFetch, `/assets/...` keeps its path,
    same-origin `/api/<service>/...` becomes `/api/<service>/*`, everything
    else is the origin only. Route spans use the pattern, never the pathname.
    Failed fetches set `error.type` to the exception name, never the message.
    No username or user id anywhere. Bodies and headers are never recorded.
  - **Context.** `StackContextManager` (not Zone): context follows only
    synchronous calls, so each `/api` fetch is the root of its own trace.
    Login (POST /login, then GET /me) is two traces. There is no parent
    span around a user action yet (follow-up).
  - **Lazy-load trade-off.** Requests made before the SDK chunk loads, such as
    the session-restore GET `/api/auth/me` on a full page load, are untraced
    (no `traceparent`). Route changes before load are buffered (20) and replayed.
  - **Failure isolation.** A rejected import, a throwing loader or `initTracing`
    is caught and leaves `fetch` untouched. The OTel diag logger is never set.
    Export errors go to the (no-op) global error handler.
  - **Tests.** Vitest with `InMemorySpanExporter` through the real
    `initTracing`. Order matters: stub fetch (`mockApi`), init, shut down in
    `afterEach`. Traced requests reach the stub with absolute URLs, so mock keys
    use `${origin}/api/...`. `vite.config.ts` has a test-only plugin and
    `test.server.deps.inline` so the OTel packages resolve with the `browser`
    condition (their `#platform` imports otherwise pick the Node exporter).
  - **nginx.** `/otlp` and `/otlp/` return 404 like the `/api` guard, so a
    Gateway misroute isn't served the SPA or a 405.
  - **Docker.** `ARG GIT_SHA` and `ARG SCARCITY_TRACING`, placed after
    `npm ci` to keep the dependency layer cached.
  - **Needs outside this module.** CI passes `build-args: GIT_SHA=${{
    github.sha }}` for client-web. The acceptance-test stack has no `/otlp`
    route, so it should build client-web with `SCARCITY_TRACING=off`.
  - **Known limits.** No sampling (add `TraceIdRatioBasedSampler` if the rate
    limit bites). The cooldown and retries are per tab. `FetchInstrumentation`
    `disable()` doesn't unwrap `fetch` (upstream). Chromium logs one "Failed to
    load resource" line per failed export; the SDK can't suppress it.
