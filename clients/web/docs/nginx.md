# clients/web: nginx and container (task 1 and #11, 2026-11-09)

Read when changing `Dockerfile`, `nginx.conf`, `nginx-main.conf` or the k8s securityContext. Moved verbatim from the decision log in `AGENTS.md`.

## Task 1: container and server block
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

## Task #11: read-only root filesystem
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
