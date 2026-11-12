# Scarcity web client

The browser client for [Scarcity](../../README.md), an online turn-based
roguelike. It renders game state and sends player intents; the server decides
all outcomes. Built with React 18, TypeScript and Vite, and served in
production as static files by nginx.

Right now it has an app shell (a `<header>` with the session controls and a
`<main>` that each screen renders into), a hello-world home page, and login and
registration against `services/auth`. Screens are listed in
[`src/routes.tsx`](src/routes.tsx).

## Routes

| path | view | notes |
|---|---|---|
| `/` | Home | Greets the logged-in player ("Welcome, alice."), or links to log in / register. |
| `/login` | Log in | Username + password. Accepts `?next=<path>` (see below). |
| `/register` | Register | Username, password, confirm password. Logs in automatically on success. Accepts `?next=`. |
| `/play` | Play (placeholder) | Protected by `RequireAuth`. |
| `/games` | Games | Lists games (`GET /api/world/resources?type=game`). Protected. |
| `/games/:gameId` | Game | Lists the game's areas; opens the area directly when there is one. Protected. |
| `/games/:gameId/areas/:areaId` | Area | Live SVG room, entities and each resource's raw state and version, updated over the WebSocket. Protected. |
| anything else | Not found | |

Protected routes sit under the `<RequireAuth />` route in `src/routes.tsx`. A
logged-out visitor is redirected to `/login?next=<the path they wanted>`, and
goes back there after logging in or registering. `next` is only honoured if
it is a same-origin path starting with a single `/` (so `//evil.example` or
`https://...` fall back to `/`), which avoids an open redirect.

## Session

- `POST /api/auth/login` returns a token; the client then calls
  `GET /api/auth/me` with it to get the user, and starts the session.
- The session (token, `expiresAt`, user) is kept in React state
  (`AuthProvider`, read with `useAuth()`) and mirrored to `sessionStorage`
  under `scarcity.session`, so it survives a reload of the tab but not
  closing it. Storage errors are ignored (the session then lasts until reload).
- On load, a stored session is validated with `GET /api/auth/me`. Until the
  answer arrives the header and protected views show "Checking session…"
  rather than the logged-out state. A 401 clears the session. If the server
  cannot be reached, the stored session is kept.
- The session ends when `expiresAt` passes: checked on load, by a timer, and
  again when the tab regains focus.
- Any authenticated call (`authRequest` from `useAuth()`) sends
  `Authorization: Bearer <token>`. A 401 ends the session.
- "Log out" only forgets the session in the browser. JWTs can't be revoked, so
  the token stays valid server-side until it expires.

Full client-side validation applies on **register** only, mirroring the auth
service: username 3–32 characters of `[A-Za-z0-9_-]`; password 8–128
characters and at most 72 bytes in UTF-8; the confirmation must match. The
**login** form only checks that both fields are filled, and leaves wrong
credentials to the server's 401. Either way the server decides. Server errors
are RFC 7807 problem documents: the form shows their `detail` (else `title`,
else a generic message). A 409 on register also marks the Username field.

Code: `src/api/client.ts` (fetch wrapper, `ApiError`), `src/api/auth.ts`
(auth calls), `src/auth/` (provider, session storage, `RequireAuth`,
validation, `?next=` handling), `src/views/` (screens).

## Requirements

Node 24 (see `.nvmrc`):

```bash
nvm use
npm ci
```

## Develop

```bash
npm run dev
```

This opens the dev server on <http://localhost:5173>. Requests to `/api/*`
are proxied to `http://localhost:8080`, which is where a locally run service
(such as `services/auth`) listens. To proxy somewhere else, set
`SCARCITY_API_TARGET`:

```bash
SCARCITY_API_TARGET=http://localhost:8081 npm run dev
```

The app always calls same-origin relative URLs (`/api/auth/...`). In
production the Gateway routes `/api/*` to the services.

### Against a local auth service

Run `services/auth` on port 8080 (see [its README](../../services/auth/README.md);
it needs Postgres), then start the dev server and open
<http://localhost:5173/register>:

```bash
npm run dev
```

Register a user, and you are logged in and sent home. Reload the page to see
the session restored from `sessionStorage` via `/api/auth/me`. If auth listens
elsewhere, point the proxy at it:

```bash
SCARCITY_API_TARGET=http://localhost:8081 npm run dev
```

The tests don't need the service: they stub `fetch`
(`src/test/mockApi.ts`).

## Tracing

Production builds record OpenTelemetry traces and POST them (OTLP/HTTP JSON,
batched every 10 s) to `/otlp/v1/traces`, which the Gateway routes to the
cluster collector. Each `/api/*` request is a span, and its `traceparent` header
carries the trace into the backend. Document load and route changes get spans
too. Spans hold no bodies, tokens, usernames or query strings. The SDK is a
separate lazy chunk loaded after the first render, and if it fails the app is
unaffected. Details: [`AGENTS.md`](AGENTS.md).

| env (build / dev-server time) | meaning |
|---|---|
| `SCARCITY_TRACING=on\|off\|console` | Default `on` for builds, `off` in `npm run dev`. `off` leaves the SDK out of the build; `console` logs spans in the browser. |
| `SCARCITY_OTLP_URL` | Export URL (default `/otlp/v1/traces`). |
| `SCARCITY_OTLP_TARGET` | Dev: proxy `/otlp/...` to a local collector, e.g. `http://localhost:4318`. |
| `GIT_SHA` | `service.version` (default: local short sha). Docker build arg too. |

```bash
SCARCITY_TRACING=on SCARCITY_OTLP_TARGET=http://localhost:4318 npm run dev
```

## Test

```bash
npm test
```

This runs Vitest once, in jsdom, with Testing Library. Use `npm run test:watch`
while developing.

## Build

```bash
npm run build
```

This type-checks everything, test files included (`tsc -b`), then writes the
production bundle to `dist/`. A type error fails the build. CI runs:

```bash
npm ci
npm test
npm run build
```

## Docker

The image builds the app with Node and serves `dist/` with
`nginxinc/nginx-unprivileged` as a non-root user on port 8080.

```bash
docker build -t scarcity-client-web:dev .
docker run --rm -p 18080:8080 \
  --read-only \
  --tmpfs /tmp \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --user 101:101 \
  scarcity-client-web:dev
```

These are the production settings (the k8s Deployment pairs
`readOnlyRootFilesystem: true` with an `emptyDir` at `/tmp`): a read-only root filesystem, no
capabilities, no privilege escalation, uid/gid 101. `/tmp` is the only
writable path (an `emptyDir` in k8s); nginx keeps its pid file and temp
directories there and logs to stdout/stderr. No start-up script writes into
`/etc/nginx`: the image empties `/docker-entrypoint.d` and sets
`worker_processes 1` statically. The plain `docker run --rm -p 18080:8080
scarcity-client-web:dev` works too.

Then:

- <http://localhost:18080/> serves the app. Unknown paths serve `index.html`
  too, so client-side routes work on reload.
- <http://localhost:18080/health> returns `200 ok` (used by the k8s probes).
- `/assets/*` (hashed build output) is cached for a year. `index.html` is
  served with `Cache-Control: no-cache`. A missing asset returns 404.
- `/api` and `/api/*` return 404 from nginx. In production the Gateway routes
  them to the services before they reach this container.

The top-level nginx config (workers, pid, temp paths, logging) is
[`nginx-main.conf`](nginx-main.conf), installed as `/etc/nginx/nginx.conf`.
The server block (routes, caching, gzip) is [`nginx.conf`](nginx.conf),
installed as `/etc/nginx/conf.d/default.conf`.

## Games and the live area

Data comes from `world`'s generic resource API (`/api/world/resources`, with
the JWT) and live changes from the `subscriptions` WebSocket
(`/api/subscriptions/ws`, same origin, JWT in the first frame). The client keeps
a cache of resources by id and applies the version rule from the root
`AGENTS.md`: a change at cached+1 replaces the state with its patch, a higher
version triggers a `GET` (with `If-None-Match`), an equal or lower one is ignored.
The socket reconnects with a 1 s to 30 s backoff and re-subscribes; a close with
code 4401 sends you back to the login page.

Dev proxies (`npm run dev`), all defaulting to `http://localhost:8080`:

| env | proxies |
|---|---|
| `VITE_WORLD_PROXY` | `/api/world` |
| `VITE_SUBSCRIPTIONS_PROXY` | `/api/subscriptions` (WebSocket) |
| `SCARCITY_API_TARGET` | the rest of `/api` (auth) |

For example, with world on 8082 and subscriptions on 8083:

```
VITE_WORLD_PROXY=http://localhost:8082 \
VITE_SUBSCRIPTIONS_PROXY=http://localhost:8083 \
npm run dev
```
