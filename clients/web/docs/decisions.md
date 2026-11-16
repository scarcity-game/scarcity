# clients/web: decision log

Read to learn why the stack, auth flow or dev proxy is as it is, or before changing one. Container and nginx decisions are in `nginx.md`, tracing in `tracing.md`.

## 2026-11-09, Task 1 (hello world)
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
  - **Container, nginx, known limits.** See `nginx.md`.

## 2026-11-09, Task 2 (login flow)
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
