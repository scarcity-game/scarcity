# clients/web: module agent notes

**Kind:** client · **Stack:** React + TypeScript + Vite, tests with Vitest + Testing
Library · **Served by:** nginx (unprivileged) on port 8080 · **k8s:**
`k8s-resources/client-web/` · **Image:** `ghcr.io/scarcity-game/scarcity-client-web`

## Purpose
The browser client. It renders game state and sends player intents. It never
decides game outcomes; the server does. It will be information dense: logs and
the exact distributions behind random outcomes.

## Deployment contract
- The production build is static files served by nginx on **8080**, as non-root
  (`nginxinc/nginx-unprivileged`, uid/gid 101).
- **Read-only root filesystem.** The image runs with `readOnlyRootFilesystem:
  true`, all capabilities dropped and no privilege escalation. The **only**
  writable path is `/tmp`, which must be mounted (an `emptyDir` in k8s,
  `--tmpfs /tmp` with docker). nginx keeps its pid and all five temp dirs
  there. Nothing else (not `/var/cache/nginx`, not `/etc/nginx`) is writable
  or needs to be. Logs go to stdout/stderr.
- `GET /health` returns `200 ok` (used for k8s probes).
- SPA fallback: unknown paths serve `index.html`.
- `/api/*` is routed by the Gateway to services, not to this container. In dev,
  Vite proxies `/api` to `http://localhost:8080` (the auth service run locally).
  Make the proxy target configurable through an env var.

## Talking to services
- Auth API: see `services/auth/AGENTS.md`. Use same-origin relative URLs (`/api/auth/...`).
- Keep the JWT in memory, with `sessionStorage` as the fallback that survives a
  reload. Send it as `Authorization: Bearer <token>`.
- World API and WebSocket subscriptions: see "Games and the live area" below.

## Build/test
`npm ci && npm test && npm run build` (this is what CI runs; `npm test` must
be non-watch).

## Formatting
Formatting follows `.prettierrc.json`. Only touched files are formatted.

## Layout of the code
- `src/main.tsx` mounts `<BrowserRouter><App/></BrowserRouter>`.
  `src/App.tsx` is `<AuthProvider><AppRoutes/></AuthProvider>`.
- `src/routes.tsx` holds the route table (`RouteObject[]` + `useRoutes`). There
  is one layout route (`src/layout/Layout.tsx`): a `<header>` with a brand link
  and the account nav, then `<main><Outlet/></main>`. Its children are:
  - the index (`HomeView`), `login`, `register`, and `*` (`NotFoundView`);
  - a `RequireAuth` route whose children are protected screens (currently
    the `play` placeholder). **Put game screens under `RequireAuth`.**
- `src/api/client.ts` has `apiRequest` (adds `Authorization: Bearer` when given
  a token), `ApiError` and `errorMessage()`. `src/api/auth.ts` has typed
  `register` / `login` / `me`.
- `src/auth/`:
  - `AuthProvider` + `useAuth()`;
  - `sessionStore` (sessionStorage);
  - `returnTo` (`safeReturnTo` for `?next=`);
  - `validation` (mirrors the auth credentials policy);
  - `RequireAuth` and `SessionCheck`.
  - `src/forms/Field.tsx` holds the accessible field and form-error components.
- In views, use `useAuth().authRequest(...)` for authenticated calls. It adds
  the token, checks expiry and ends the session on a 401.
- Tests sit next to code (`*.test.ts(x)`).
  - `src/test/renderAt.tsx` renders `<App/>` in a `MemoryRouter` at a given
    path, and `currentLocation()` reads the router's current location.
  - `src/test/mockApi.ts` stubs `fetch` with a route table and provides
    `json`/`problem` response builders.
  - `src/test/setup.ts` loads the jest-dom matchers and resets sessionStorage
    and stubbed globals around each test.
- Config: the Vitest config is inside `vite.config.ts`. `tsconfig.app.json`
  covers all of `src`, tests included. `tsconfig.node.json` covers
  `vite.config.ts`.

## Configuration
| env | where | meaning |
|---|---|---|
| `SCARCITY_API_TARGET` | `npm run dev` only | Where Vite proxies `/api` and `/api/...`. Defaults to `http://localhost:8080`. The dev server itself is on 5173 (`strictPort`). |
| `SCARCITY_TRACING` | build and dev (Docker ARG too) | `on` \| `off` \| `console`. Default `on` for `vite build`, `off` for `vite` dev and Vitest. `off` removes the OTel chunk from the output. `console` logs spans to the browser console. Empty counts as unset; other values fail the build. |
| `VITE_WORLD_PROXY` | `npm run dev` only | Proxy target for `/api/world`. Default `http://localhost:8080`. |
| `VITE_SUBSCRIPTIONS_PROXY` | `npm run dev` only | Proxy target for `/api/subscriptions` (WebSocket). Default `http://localhost:8080`. |
| `SCARCITY_OTLP_URL` | build and dev | Where spans are POSTed. Default `/otlp/v1/traces`. |
| `SCARCITY_OTLP_TARGET` | dev server and `vite preview` | Proxies `/otlp/...` to a local collector (e.g. `http://localhost:4318`), rewriting `/otlp/x` to `/x` like the Gateway. Unset: no proxy. |
| `GIT_SHA` | build (Docker ARG too) | `service.version` on spans. Default: `git rev-parse --short HEAD`, else `unknown`. Must match `[0-9A-Za-z._-]{1,64}`. The Docker context has no `.git`, so CI must pass it as a build arg. |

## Games and the live area (task #31)
- **Routes** (under `RequireAuth`): `/games` (`GamesView`), `/games/:gameId`
  (`GameView`, redirects when there is exactly one area),
  `/games/:gameId/areas/:areaId` (`AreaView`). The path params are the bare
  uuids, without the type prefix (`/games/<uuid>/areas/<uuid>`).
  `src/resources/routeIds.ts` is the only place that converts: `gamePath` /
  `areaPath` build links, `segmentToId(type, segment)` turns a param back into
  `game:<uuid>`; an old-style segment that already has the right prefix is
  accepted, a wrong prefix gives `NotFoundView`. Resource ids stay prefixed
  everywhere else (cache, WebSocket) and are `encodeURIComponent`ed in REST URLs. `/play` is still the placeholder.
- **`src/resources/`**: `versionRule.ts` (pure `decideChange`, `decideDigestEntry`),
  `cache.ts` (`ResourceStore`, immutable snapshots, `useResources`; a patch
  replaces `state`; stale marks), `worldApi.ts` (list/children/get; paging with
  `size=200`; `If-None-Match` from the cached version, 304 keeps the cache),
  `sync.ts` (`AreaSync`: handles `changes` and `digest`, coalesces GETs per id,
  refetches unknown children after the area).
  - **Created and destroyed resources** (`docs/resources.md`): a change for an
    id we do not hold, with a patch, is a *created* resource and is stored as
    it is (`children` or `[]`, no GET); without a patch it is fetched.
    `children` in a change replaces the cached child list (only when the change
    is applied; a stale change changes nothing). `{id, type, deleted: true}`
    removes the resource. `handleChanges` applies the whole batch inside
    `ResourceStore.batch`, which notifies subscribers once, so the view never
    shows a parent listing a child that is gone. `ResourceStore` has `remove`,
    `ids`, `batch`, and `applyPatch(id, version, patch, children?)`.
  - **`digest` is authoritative**: cached resources below the area (children,
    their children) that the digest does not list are dropped; listed unknown
    ids are fetched; the version rule applies to the rest. The area itself and
    other areas' resources are never dropped.
- **`src/ws/subscriptionsClient.ts`**: framework-free; injectable WebSocket
  constructor and timers. Auth first, subscribe only after `ready`, ping every
  25 s, backoff 1 s doubling to 30 s (reset on `ready`), re-auth and
  re-subscribe on reconnect, close 4401 stops and calls `onUnauthorized`
  (`AuthProvider.rejectSession`). Protocol events at `console.debug` only.
- **Store is per area page**, created on mount and cleared on entering an area;
  unmount unsubscribes and stops the socket (so logout closes it).
- **`src/components/RoomSvg.tsx`**: viewBox in world units, y flipped
  (`svgY = height - y`), fixed = square, mobile = circle, name label. An entity
  with a numeric `radius > 0` in its state is a circle of that radius. Any
  entity with a `position` is drawn (unknown kinds such as `pebble`/`rubble`
  get the square marker); the view draws whatever the area currently lists.
- `apiRequest` has an `ifNoneMatch` option; a 304 resolves to `undefined`.
- **Tracing**: `/api/world` fetches already match `isTracedUrl`; WebSocket frames are not traced.
- **Env**: `VITE_WORLD_PROXY`, `VITE_SUBSCRIPTIONS_PROXY` (dev only, default
  `http://localhost:8080`); proxies sit before the generic `/api` one, `ws: true` for subscriptions.
- **Verified** with a mock auth/world/WebSocket server in a real browser: live
  movement, reconnect with backoff, 4401 redirect, unsubscribe on leave and logout.
- **Known limits**: games and areas lists show only the first page (50);
  a handshake rejection (HTTP 401 on upgrade) looks like 1006 and retries forever;
  the initial load and the first digest can GET the same entity twice.

## Game time readout
- `src/game/clock.ts` `currentGameTime(clock, nowMillis)` implements the
  `docs/resources.md` "Game time" formula; null for a missing or invalid
  `area.state.clock`. `src/components/GameTimeReadout.tsx` re-reads the anchor
  from props every 100 ms (interval cleared on unmount), so a new anchor from an
  area change shows on the next refresh. Shown as `game time 1042.37 (rate 1.0)`
  or `game time —`.
- **Deferred**: the browser clock may differ from the server's, which shifts the
  readout (same class as the session-expiry limit); animating the action in
  progress is issue #62.
- **Test note**: the first `AreaView` test used to fail about 1 run in 4 under the
  parallel full suite (1 s `findByRole` cold start); it now waits up to 4 s.

## Area log panel (2026-11-13)
The area view shows the area's log (`docs/log.md`) in a panel beside the room (below it at narrow widths).
- `src/log/logStore.ts` `LogStore`: loads `GET /api/world/log?area=&after=&limit=500` (via `logApi.fetchLogPage` and
  `authRequest`), first asks for one entry (`limit=1`) to learn `first` and `last` and starts at `max(first - 1, last - max)`, so the first load is at most `max` entries; then pages until `last`. After that it follows `logSeq` (see below) or, without a live socket, polls every `pollMs` (default 1000; `AreaView` passes 5000), one request at a time, `setTimeout`. Holds at most
  the newest 2,000 entries (`MAX_ENTRIES`). If the server's `first` is greater than `lastSeq + 1` it drops what it
  holds and restarts from `first - 1`. A fetch error is kept in `getError()` and polling goes on. `start()` / `stop()` /
  `clear()`; `useLogEntries` and `useLogError` are the React hooks. The store knows nothing about the view, so the
  replay can reuse it (pass a different `FetchPage`).
- Following the log live (2026-11-13; `docs/log.md`): `ServerMessage.logSeq` reaches `onDigest(area, versions, logSeq?)` and `onChanges(area, changes, logSeq?)`; `AreaView` calls `logStore.notifyLogSeq(n)` for each and `logStore.setLive(status === 'live')` from `onStatus`. `notifyLogSeq(n)`: `n <= last seq held` does nothing; otherwise one read; during a read, a second read follows only if `n` is higher than any `n` that read was already asked for (repeats of the same `n` are not doubled). `setLive(true)` reads once and keeps the `pollMs` timer until the first `logSeq` arrives (an older `subscriptions` never sends one, so the panel does not freeze); after that no timer runs. `setLive(false)` resumes polling. Tests: `logStore.live.test.ts` (fake timers), `views/AreaView.log.test.tsx` (real timers, 3 s silence). Because the socket going live now reads the log, tests that count requests must filter `/api/world/log` out.
- `src/log/logTree.ts` `buildTrees(entries)`: pure; one tree per `root`, children under `cause` in `seq` order, an entry
  whose cause is not loaded sits at the top of its root's group. `routine` = only `orbit-step`/`wait` start and end
  entries. Rebuilt from the whole list on each change, so an `action-end` that arrives in a later poll lands under its start.
- `src/log/format.ts`: `drawText` (every key of a draw, bounds as `[min, max)`, floats with a decimal point, the
  categorical `value` as an index), `describe`, `entityOf` (resource, else effect target, else the first entity in
  `changes`, else effect source), `shortId`, `formatLogTime` (`toFixed(2)`, same as the game time readout).
- `src/log/LogPanel.tsx`: routine trees are collapsed to their first line by default, others expanded; every tree
  with more than one entry has a toggle. Follows the newest entry unless scrolled up (8 px slop). Hover or focus of
  an entry calls `onHighlight(entityId)`; the innermost entry handles the event (they nest and the events bubble).
  `AreaView` passes it to `RoomSvg` as `highlightId`, which draws a dashed ring (`.highlight-ring`).
- Names come from the resource cache, else from `changes[].state.name` seen in the log, else `type:…last4`.
- Not done: the log for `kind` filters and a "jump to newest" control. `LogPanel` also takes `currentSeq` (marked with `aria-current` and `.log-current`, scrolled into view, its tree expanded) and `onSelect(seq)` (click, or Enter/Space on the entry itself); the replay uses both.
- Tests: `format.test.ts`, `logStore.test.ts` and `logStore.live.test.ts` (fake timers), `logTree.test.ts`, `LogPanel.test.tsx` (a full throw
  tree), and the log test at the end of `views/AreaView.test.tsx`. Vitest's `testTimeout` is 20 s (cold start of the first test in a file).

## Area replay (2026-11-13; contract `docs/log.md`, "Playback, not re-simulation")
The area view has a **Replay** button. Replay mode replaces the live room (same `RoomSvg`, resource panel and log panel) with the area as the log says it was; live sync and the live `LogStore` keep running underneath and **Back to live** is instant (the replay is dropped, nothing is refetched). The client applies entries; it never computes rules.
- `src/replay/replay.ts`: pure `applyEntry(map, entry)` (ops `snapshot` clears the map and makes the area list every other snapshot resource as a child; `changed` replaces state and keeps the old version when `version` is null, ignores unknown ids; `created` adds under `parent`; `destroyed` removes the resource, its descendants and its place in the parent's child list) and `Replay(snapshot, entries)`: `seekIndex/seekSeq/seekTime` (forward is incremental, backward rebuilds from the snapshot; `seekTime(t)` applies every entry with `time <= t`, so same-time entries are all applied in `seq` order), `rebuildAt(i)` (pure, for tests), `resources` (a new Map instance only when the state changed), `effectRoots` / `prevEffectRoot` / `nextEffectRoot` (root `action-start` entries whose tree has an `effect` entry, i.e. a throw).
- `src/replay/loader.ts` `loadReplay(fetchPage, {signal, pageSize, onProgress})`: walks `kind=snapshot` pages (5 at a time) to the latest snapshot, then pages every entry after it up to the `last` the first page reported (the range is fixed; later entries are ignored). `no-snapshot` is a result, not an error. A page whose `first` is above the snapshot's `seq` means the log was pruned underneath: it restarts once, then throws `ReplayPrunedError`. Abort throws an `AbortError`. `logApi.fetchLogPage` got an optional `kind` argument.
- `src/replay/player.ts` `ReplayPlayer` (external store, `useReplayState` / `useOptionalReplayState`): a playback clock (`gameTime`) that advances from wall time at 1x, 4x or 16x on a 50 ms interval and stops at the end of the range; `seekTime` (scrubber), `seekSeq` (log click), `stepEntry(±1)`, `jumpRoot('prev'|'next')`; stepping and jumping pause. Between an `action-start` and its end the entity stays where its state says (no interpolation; that is #62). `now` and `intervalMs` are injectable for tests.
- `src/replay/useReplaySession.ts` (idle, loading with progress, error, active), `ReplayControls.tsx` (banner `role=status` with game time and entry seq, previous/next action, previous/next entry, play/pause, speed group, `Game time` range scrubber, Back to live; all native buttons, so keyboard reachable; they wrap at narrow widths).
- Fixture and tests: `src/replay/throwFixture.ts` (a full throw: pebble created seq 14 and destroyed 19, pillar `hp` 97.5 at 17, rubble 20 to 23), `replay.test.ts`, `loader.test.ts`, `player.test.ts`, `log/LogPanel.replay.test.tsx`, `views/AreaView.replay.test.tsx`.
- Known limits: a snapshot gives no child lists, so the area's children are taken to be all the other resources in it (true while areas hold only entities); the replay panel lists the whole range (at most what one area's log holds, no 2,000 cap); entries inside a routine tree collapsed in the log panel can only be reached by stepping (the tree of the current entry opens itself); `Next entry` is disabled only at the end of the range.

## Decision log
- **2026-11-09, Task 1 (hello world):**
  - **Stack.** React 18.3.1, TypeScript 5.9.3 (strict) and Vite 8. Tests use
    Vitest 5 with jsdom and Testing Library. Versions are pinned exactly in
    `package.json`.
    - jsdom stays on 29.x, because 30+ requires Node >=24.15 and some local
      machines run 24.2.
    - TypeScript stays on 5.x for now.
    - `.nvmrc` is `24` and `engines.node` is `>=24`.
  - **Router.** `react-router` 7 (the core package; v7's `react-router-dom`
    only re-exports it), in declarative mode with `useRoutes`. The data router
    (`createBrowserRouter`, loaders) is not used yet; switch to it if loaders
    or actions become useful.
  - **Headings.** The header brand "Scarcity" is a link, not a heading. Each
    view owns exactly one `<h1>`. The header is where session controls
    (username, log out) go once login exists.
  - **Build.** `npm run build` is `tsc -b && vite build`, so a type error,
    including one in a test file, fails the build and CI. There is no ESLint
    yet.
  - **Dev proxy.** The key is the regex `'^/api(/|\\?|$)'` (Vite tests it against path + query), so it matches whole
    path segments like the Gateway's PathPrefix does, and `/apiary` is not
    proxied. `changeOrigin` is on. An empty `SCARCITY_API_TARGET=` is used
    as-is (`??` semantics), which breaks the proxy, so unset the variable
    instead.
  - **Container.** `node:24-alpine` build stage, then
    `nginxinc/nginx-unprivileged:1.30.6-alpine` (current stable; uid 101).
    - The base image's default html is removed, so only `dist/` is served.
    - Worker count was originally autotuned from the CPU limit
      (`NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE=1`). Superseded by Task #11:
      it is now a static `worker_processes 1` in `nginx-main.conf`.
  - **nginx (`nginx.conf` → `conf.d/default.conf`).**
    - `= /health` returns 200 `ok` as text/plain.
    - `/assets/` is cached for a year as immutable. A missing asset is a
      plain 404 (no fallback, and no cache header on the 404).
    - Everything else falls back to `/index.html` with `Cache-Control: no-cache`.
    - `= /api` and `/api/` return 404, so a Gateway misroute is obvious and
      doesn't serve HTML.
    - Also: gzip on, `server_tokens off`, `absolute_redirect off`.
  - **Known limits.**
    - The root filesystem must be read-only with `/tmp` as the only writable
      path (Task #11, below). A pod that sets `readOnlyRootFilesystem: true`
      **without** the `/tmp` emptyDir crash-loops on
      `[emerg] mkdir() "/tmp/client_temp" failed (30: Read-only file system)`.
    - `node:24-alpine` floats, which is acceptable for a build stage.
    - No security headers (CSP etc.) yet.
- **2026-11-09, Task 2 (login flow):**
  - **Auth state.** It is a discriminated union, with the token held in
    React state:
    - `checking`: a stored session is being validated;
    - `anonymous`: carries `reason: logout | expired | unauthorized | null`;
    - `authenticated`.
    - The session is mirrored to sessionStorage key `scarcity.session` as
      `{token, expiresAt, user}`. Every storage access is in try/catch, and a
      corrupt entry is dropped.
  - **Login.** `login()` does POST /login, then GET /me with the new token to
    get the user, so the client never decodes the JWT. Register calls
    `register()` then `login()`. If that automatic login fails, the form says
    the account was created and links to Log in.
  - **Restore on load.**
    - An expired stored session is cleared without a request.
    - Otherwise the state is `checking` while /me runs, with an
      AbortController so StrictMode's double effect is safe.
    - 200 refreshes the user and 401 clears the session.
    - A network error or 5xx **keeps** the session, so an outage doesn't log
      players out. The next 401 ends it.
  - **Expiry.** A timer fires at `expiresAt` (clamped to the setTimeout max and
    re-armed). Expiry is re-checked on `focus` and `visibilitychange` and before
    every `authRequest`. A 401 ends the session only if it is for the current
    token. Uses the client clock, so there is no skew handling yet.
  - **Return-to.** The `?next=` query param, filtered by `safeReturnTo`.
    - Allowed: a same-origin path starting with exactly one `/`.
    - Rejected: `//`, backslashes, whitespace and control characters, and
      `/login` or `/register` as targets.
    - Anything else falls back to `/`.
    - A logged-in user who visits /login or /register is redirected to `next`.
  - **Errors.** `ApiError{kind: http|network|invalid-response, status, title,
    detail, fieldErrors}`. The message is chosen in this order:
    1. `detail`;
    2. on login 401, "Invalid username or password.";
    3. `title`;
    4. a generic message.
    - The server's 400 `errors` extension is also shown inline on the
      matching field.
    - A 409 on register also marks the Username field inline (the server's
      detail, or "That username is taken.") and moves focus to it.
  - **Validation.**
    - Register mirrors the server policy, counted in code points:
      - username 3-32 characters of `[A-Za-z0-9_-]`;
      - password 8-128 characters and at most 72 UTF-8 bytes (measured with
        TextEncoder; the message explains why multibyte characters count
        more);
      - confirm must match.
    - Login only checks that both fields are filled and leaves the rest to the
      server's 401.
  - **Logout.** Client side only. It clears the session and navigates to `/`
    inside one `startTransition`, so `RequireAuth` doesn't redirect to
    `/login?next=...` mid-logout.
  - **Tests.** `fetch` is stubbed by a small route table helper
    (`src/test/mockApi.ts`); there is no MSW dependency.
- **2026-11-09, Task: read-only root filesystem (#11):**
  - **Goal.** The image runs with `docker run --read-only --tmpfs /tmp
    --cap-drop ALL --security-opt no-new-privileges --user 101:101` (k8s:
    `readOnlyRootFilesystem: true` plus an `emptyDir` at `/tmp`). `/tmp` is
    the only writable mount; no other tmpfs or emptyDir is needed.
  - **Own top-level config.** `nginx-main.conf` is COPY'd over
    `/etc/nginx/nginx.conf`, so every writable path is explicit in this repo
    instead of inherited from the base image:
    - `pid /tmp/nginx.pid`;
    - `client_body`, `proxy`, `fastcgi`, `uwsgi` and `scgi` temp paths under
      `/tmp` (their compiled-in defaults are under `/var/cache/nginx`, and
      nginx creates them at startup even if unused);
    - no `*_cache_path` is configured; a future one must go under `/tmp`.
    - Otherwise it matches the base image's file: `error_log ... notice`, the
      same `main` access log format, logs to `/var/log/nginx/*.log` (symlinks
      to stdout/stderr), `worker_connections 1024`, `sendfile on`,
      `keepalive_timeout 65`, `include conf.d/*.conf`.
    - The server block (`nginx.conf` → `conf.d/default.conf`) is unchanged.
  - **`worker_processes 1`, static.** The pod is limited to 200m CPU and
    64Mi, so one worker is enough and extra workers only cost memory. It
    replaces the AUTOTUNE env, whose entrypoint script rewrote nginx.conf at
    startup (which fails on a read-only root). Raise it together with the
    CPU limit.
  - **Entrypoint scripts: the directory is emptied** (`rm -f
    /docker-entrypoint.d/*` as root in the Dockerfile, then back to `USER
    101`). We keep the base image's ENTRYPOINT/CMD, and with an empty
    directory it logs "No files found ... skipping configuration" and execs
    nginx. We chose emptying over deleting selected scripts because none of
    the four is useful to us: `10-listen-on-ipv6` already skipped our file
    (root-owned, so "can not modify"), `15-local-resolvers` only feeds
    templates, `20-envsubst-on-templates` has no templates to render, and
    `30-tune-worker-processes` is replaced by the static setting. An empty
    directory also means a future base image that adds a script can't
    silently start writing under `/etc/nginx`. We chose it over overriding
    ENTRYPOINT so the base image's start-up contract stays intact.
  - **Listen unchanged.** IPv4 `0.0.0.0:8080` only, as before (checked with
    `netstat -lnt` in both the old and new image).
  - **Verified** against the real container: `/`, `/play`, `/some/deep/link`
    and `/index.html` serve index.html with `no-cache`; `/health` is 200
    `ok`; `/assets/<file>` is immutable for a year; a missing asset, `/api`
    and `/api/x` are 404; gzip works. Headers are identical to the previous
    image. 500 concurrent requests, also under `--memory 64m --cpus 0.2`, all
    succeeded with no warn/error lines. Memory is about 2.6 MiB idle, under
    5 MiB after a 500-request burst, and peaked at 16.7 MiB of 64 MiB under
    20k gzip requests at concurrency 200 (`--memory 64m --cpus 0.2`).
    Restarting with a stale pid file in a persistent /tmp is clean.
  - **Deployment needs** (owned outside this module): the client-web
    Deployment should set `securityContext` (`readOnlyRootFilesystem: true`,
    `runAsNonRoot: true`, `runAsUser/runAsGroup: 101`,
    `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`) and mount
    an `emptyDir` at `/tmp` (a small `sizeLimit`, e.g. 16Mi, is plenty;
    nothing large is written there because we don't proxy or accept uploads).
- **2026-11-09, Task: browser tracing with OpenTelemetry (#18):**
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
