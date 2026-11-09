# clients/web-acceptance-tests: module agent notes

**Kind:** test · **Stack:** Node 24, TypeScript, Playwright (`@playwright/test`,
Chromium only), docker compose · **Deploys:** nothing · **Tests:** `clients/web`
through the browser, against `services/auth`

## Purpose
Real-browser end-to-end tests for the web client. They run against a local
stack that mirrors production routing, so they catch what unit tests with a
stubbed `fetch` cannot: the Gateway's path routing, the client's nginx, the
real auth API and its error bodies, sessionStorage across reloads, and redirects.

This module only **reads** the other modules. It builds their Dockerfiles and
drives their UI. If a test needs a change in `clients/web` or `services/auth`
(a stable locator, say), report it to the repo agent; don't edit them from here.

## How it runs
`npm test` (= `scripts/run.sh`) is the single entry point, locally and in CI:

1. Check that Docker, the compose plugin and `node_modules` are present, and fail with a clear message if not.
2. `playwright install chromium`, with `--with-deps` on Linux (OS packages via sudo, as on GitHub's ubuntu runners).
3. `docker compose -p scarcity-e2e -f stack/compose.yaml up --build --wait`. This builds auth and client-web from their own Dockerfiles and waits on every healthcheck.
4. `playwright test "$@"` against `E2E_BASE_URL=http://127.0.0.1:$E2E_PORT`.
5. An EXIT trap (INT/TERM are turned into exits) always runs
   `docker compose down -v --remove-orphans`. When the exit code is non-zero, it first
   writes `docker compose ps -a` + `logs` to `test-results/stack.log`.
6. Exit with Playwright's exit code (2 when a precondition fails).

The script resolves paths from its own location, so it works from any cwd.
CI should run `npm ci && npm test` in this directory.

### The stack (`stack/compose.yaml`, project name `scarcity-e2e`)
| service | from | notes |
|---|---|---|
| `postgres` | `postgres:18.6` | throwaway creds `e2e` / `e2e-throwaway`, db `scarcity`, data on tmpfs (no volume). Healthcheck `pg_isready -h 127.0.0.1` |
| `auth` | `services/auth/Dockerfile`, context repo root | profile `k8s`, `SPRING_DATASOURCE_*` → postgres, no `AUTH_JWT_PRIVATE_KEY_PATH` (ephemeral key), `AUTH_PASSWORD_BCRYPTSTRENGTH=10`. Healthcheck `/actuator/health/readiness` over bash `/dev/tcp`, because the JRE image has no curl or wget |
| `client-web` | `clients/web/Dockerfile`, context `clients/web` | healthcheck `wget /health` |
| `gateway` | `nginxinc/nginx-unprivileged:1.30.6-alpine` + `stack/gateway.conf` | the only published port: `127.0.0.1:${E2E_PORT:-18473}` → 8080. Healthcheck goes through its own routes (`/health` and the JWKS) |

`gateway.conf` mirrors `k8s-resources/platform/httproute.yaml`:
- `= /api/auth` and `/api/auth/` go to `auth:8080`, with the path unchanged;
- everything else goes to `client-web:8080`.

That is PathPrefix semantics: `/api/authx` goes to the client, which 404s
`/api/*`. **When a service adds an HTTPRoute rule, add the matching locations
here and the service to the compose file.**

## Configuration
| env | used by | meaning |
|---|---|---|
| `E2E_PORT` | compose, run.sh, config | Host port of the gateway. Default `18473`. |
| `E2E_BASE_URL` | `playwright.config.ts` | Base URL for the tests. run.sh sets it to `http://127.0.0.1:$E2E_PORT`; with `test:only` it defaults to the same. |
| `CI` | `playwright.config.ts` | Turns on 1 retry, 2 workers, `forbidOnly`, and the `github` + `list` + `html` reporters. |

## Scripts
- `test`: the full run described above.
- `test:only`: Playwright only, against an already-running stack.
- `stack:up` / `stack:down` / `stack:logs`: the compose stack on its own.
- `test:ui`, `report`, `browsers`, `typecheck`.

## Artifacts (CI should upload both, `if: always()`)
- `test-results/`: for failed tests only, `trace.zip`, a screenshot and a video
  (`trace: retain-on-failure`, `screenshot: only-on-failure`, `video: retain-on-failure`).
  On a failed run it also holds `stack.log`. Playwright empties this directory when a run starts.
- `playwright-report/`: the HTML report (`open: 'never'`).

## Writing tests
- Each test creates its own user with `uniqueUsername()` (`e2e_` + 12 hex characters),
  so tests are independent and run fully parallel against one database. The
  database is thrown away at teardown.
- Use accessible locators (`getByRole`, `getByLabel` with `exact: true`, since
  "Password" also matches "Confirm password", and `getByText`). Scope header
  controls to `getByRole('navigation', {name: 'Account'})`.
- Set up through the API (`createUser(request, ...)`) when the UI path isn't what the test is
  about. The register flow itself is always exercised through the UI.
- Assert on the user-visible text that the real server produces. For example, the 401
  alert shows the server's detail "Invalid username or password" (no period),
  because `errorMessage()` prefers `detail` over the client's fallback text.

## Decision log
- **2026-11-09, Task: Playwright e2e (issue #8):**
  - **Playwright 1.64.1, pinned exactly.** 1.65.0 was only 4 days old. TypeScript
    5.9.3 and `@types/node` 24.19.2 match `clients/web`. There is no `"type": "module"`,
    because Playwright transpiles the TS itself; `tsc --noEmit` is only the typecheck.
  - **Chromium only.** Firefox and WebKit would triple browser install time for little gain at this stage.
  - **Compose in the module, fixed project name.** The run script also passes `-p`, and
    does a best-effort `down` before `up`, which clears leftovers from a run that was killed with SIGKILL.
  - **Gateway is nginx-unprivileged 1.30.6**, the same image as the client runtime. It is
    pulled once, and it stands in for Envoy with plain `location` blocks.
  - **Gateway bound to 127.0.0.1 only.** Nothing in the stack is reachable from the LAN.
  - **`k8s` profile for auth.** It mirrors production (graceful shutdown, small Hikari pool).
    The compose `down --timeout 10` is enough because there are no in-flight requests at teardown.
  - **bcrypt cost 10** through relaxed binding (`AUTH_PASSWORD_BCRYPTSTRENGTH` →
    `auth.password.bcrypt-strength`). 10 is the minimum the service allows. It keeps
    register and login quick without changing the code path.
  - **Postgres on tmpfs.** It is faster, and `down -v` has nothing to leak. Mounting over
    the image's `VOLUME` path also prevents the anonymous volume.
  - **Chromium install inside `npm test`.** The browser download is cached in
    `~/Library/Caches/ms-playwright` (macOS) or `~/.cache/ms-playwright` (Linux), so
    warm runs only pay for a version check. `--with-deps` is passed only on Linux.
  - **Retries: 1 on CI, 0 locally.** A retry that passes still shows as flaky in the report.
  - **No `--pull`.** The build is a plain `up --build`, so cached base images are used.
    Don't add `--pull`, `pull_policy: always` or `docker compose pull`: on the maintainer's
    Mac they invoke `docker-credential-desktop`, which pops up macOS permission dialogs.
    BuildKit still resolves `FROM` (and the auth Dockerfile's `# syntax=docker/dockerfile:1`)
    metadata from the registry, and in an agent sandbox that credential helper can hang
    at "resolve image config for docker.io/docker/dockerfile:1". Agents work around it by
    pointing `DOCKER_CONFIG` at a scratch directory whose `config.json` has no `credsStore`
    (keep `currentContext: desktop-linux` and `cliPluginsExtraDirs`). Every image used is
    public, so anonymous lookups work. CI and normal terminals don't need this.
