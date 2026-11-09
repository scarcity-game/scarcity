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
- WebSocket resource subscriptions come later; see "Resources" in `/AGENTS.md`.

## Build/test
`npm ci && npm test && npm run build` (this is what CI runs; `npm test` must
be non-watch).

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
