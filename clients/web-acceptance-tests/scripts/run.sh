#!/usr/bin/env bash
# The single entry point for the acceptance tests (`npm test`, also in CI).
#
#   1. installs Playwright's Chromium (plus OS packages on Linux),
#   2. builds and starts the compose stack and waits until it is healthy,
#   3. runs Playwright (extra arguments are passed through),
#   4. always tears the stack down (also on failure and Ctrl-C), after saving
#      `docker compose logs` to test-results/stack.log if anything failed,
#   5. exits with Playwright's exit code.
#
# Works from any cwd. Env: E2E_PORT (host port of the gateway, default 18473).
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

COMPOSE_FILE="$MODULE_DIR/stack/compose.yaml"
PROJECT="scarcity-e2e"
RESULTS_DIR="$MODULE_DIR/test-results"
export E2E_PORT="${E2E_PORT:-18473}"
export E2E_BASE_URL="http://127.0.0.1:${E2E_PORT}"

compose() {
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

log() {
  printf '\n[e2e] %s\n' "$*" >&2
}

if ! command -v docker >/dev/null 2>&1; then
  log "ERROR: docker is not installed or not on PATH. The acceptance tests need Docker with the compose plugin."
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  log "ERROR: cannot reach the Docker daemon. Start Docker (Docker Desktop on macOS) and try again."
  exit 2
fi
if ! docker compose version >/dev/null 2>&1; then
  log "ERROR: 'docker compose' (compose v2 plugin) is not available."
  exit 2
fi
if [ ! -x "$MODULE_DIR/node_modules/.bin/playwright" ]; then
  log "ERROR: Playwright is not installed. Run 'npm ci' in $MODULE_DIR first."
  exit 2
fi

stack_started=0
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [ "$stack_started" -eq 1 ]; then
    if [ "$status" -ne 0 ]; then
      mkdir -p "$RESULTS_DIR"
      log "Run failed (exit $status); saving stack logs to test-results/stack.log"
      compose ps -a >"$RESULTS_DIR/stack.log" 2>&1 || true
      compose logs --no-color --timestamps >>"$RESULTS_DIR/stack.log" 2>&1 || true
    fi
    log "Tearing down the stack"
    compose down -v --remove-orphans --timeout 10 || true
  fi
  exit "$status"
}
trap cleanup EXIT
# Turn signals into a normal exit so the EXIT trap runs the teardown.
trap 'exit 130' INT
trap 'exit 143' TERM

log "Installing Playwright Chromium"
if [ "$(uname -s)" = "Linux" ]; then
  # Also installs the OS packages Chromium needs (uses sudo on CI runners).
  npx --no-install playwright install --with-deps chromium
else
  npx --no-install playwright install chromium
fi

log "Starting the stack on $E2E_BASE_URL (project $PROJECT)"
stack_started=1
# Clear leftovers from an earlier run that was killed hard.
compose down -v --remove-orphans --timeout 5 >/dev/null 2>&1 || true
compose up --build --wait --wait-timeout 300

log "Running Playwright against $E2E_BASE_URL"
status=0
npx --no-install playwright test "$@" || status=$?
exit "$status"
