# Scarcity web acceptance tests

Real-browser end-to-end tests for the web client (`clients/web`), written with
[Playwright](https://playwright.dev). They run against a local copy of the
production stack, started with docker compose:

```
browser -> gateway (nginx, routes like the k8s HTTPRoute) -> auth  -> postgres
                                                          -> client-web
```

## Requirements
- Node 24 (`nvm use` reads `.nvmrc`).
- Docker with the compose v2 plugin (Docker Desktop on macOS). Docker must be running.

## Run everything (what CI does)

```bash
cd clients/web-acceptance-tests
npm ci
npm test
```

`npm test` installs Playwright's Chromium, builds and starts the stack, waits
until it is healthy, runs the tests, and always tears the stack down again,
even when tests fail or you press Ctrl-C. It exits with Playwright's exit code.
Arguments after `--` go to Playwright, for example `npm test -- -g "wrong password"`.

The gateway listens on `http://127.0.0.1:18473`. Set `E2E_PORT` to use another port:

```bash
E2E_PORT=19999 npm test
```

## Iterate on tests against a running stack

```bash
npm run stack:up
npm run test:only
npm run test:only -- --headed -g "reload"
npm run stack:down
```

`test:only` uses `E2E_BASE_URL`, or `http://127.0.0.1:${E2E_PORT:-18473}`.
Use the same `E2E_PORT` for `stack:up` and `test:only`. You can also point
`test:only` at another deployment:

```bash
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:only
```

Other scripts: `npm run stack:logs`, `npm run test:ui` (Playwright UI mode),
`npm run report` (open the last HTML report), `npm run typecheck`.

## When a test fails
- `test-results/`: a trace, screenshot and video for each failed test. Open a trace with
  `npx playwright show-trace test-results/<test>/trace.zip`.
- `test-results/stack.log`: `docker compose ps` and the logs of every container,
  written by `npm test` before teardown when the run failed.
- `playwright-report/`: the HTML report (`npm run report`).

Both directories are gitignored.

## Troubleshooting
- **The build hangs at "resolve image config for docker.io/docker/dockerfile:1".**
  Docker Desktop's credential helper is stuck. All images here are public, so run
  with a `DOCKER_CONFIG` directory whose `config.json` has no `credsStore`:

  ```bash
  mkdir -p /tmp/e2e-docker
  echo '{"currentContext":"desktop-linux","cliPluginsExtraDirs":["'"$HOME"'/.docker/cli-plugins"]}' > /tmp/e2e-docker/config.json
  DOCKER_CONFIG=/tmp/e2e-docker npm test
  ```
- Base images are taken from the local cache; the build never forces a pull (`--pull`).
