import { execFileSync } from 'node:child_process';
import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

// In dev, /api is proxied to a locally running service (the auth service by
// default). Override with SCARCITY_API_TARGET, e.g. http://localhost:8081.
// In production the Gateway routes /api/* to the services, not to nginx.
const apiTarget = process.env.SCARCITY_API_TARGET ?? 'http://localhost:8080';

type TracingMode = 'on' | 'off' | 'console';

// Browser tracing (src/tracing/). Everything here is read once, at build (or
// dev-server start) time, and baked into the bundle as constants.
//
// SCARCITY_TRACING: on | off | console. Defaults to `on` for `vite build` and
// `off` for `vite` (dev) and Vitest. `off` drops the OpenTelemetry chunk from
// the build entirely. `console` logs spans to the browser console instead of
// exporting them. An empty value counts as unset.
function tracingMode(command: 'build' | 'serve', mode: string): TracingMode {
  const value = process.env.SCARCITY_TRACING?.trim().toLowerCase();
  if (!value) return command === 'build' && mode !== 'test' ? 'on' : 'off';
  if (value === 'on' || value === 'off' || value === 'console') return value;
  throw new Error(`SCARCITY_TRACING must be on, off or console (got '${value}')`);
}

// service.version on browser spans: GIT_SHA (the Docker build arg), else the
// local checkout's short sha, else "unknown". The Docker context has no .git,
// so the image build relies on the build arg.
function gitSha(): string {
  const fromEnv = process.env.GIT_SHA?.trim();
  if (fromEnv && fromEnv !== 'unknown') return sanitizeVersion(fromEnv);
  try {
    const sha = execFileSync('git', ['rev-parse', '--short', 'HEAD'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      timeout: 5000,
    });
    return sanitizeVersion(sha.trim());
  } catch {
    return 'unknown';
  }
}

function sanitizeVersion(value: string): string {
  return /^[0-9A-Za-z._-]{1,64}$/.test(value) ? value : 'unknown';
}

// Vitest resolves modules with Node's conditions, even for jsdom tests. Make
// the jsdom (client) environment resolve like the browser build does, so the
// OpenTelemetry packages' `#platform` imports pick their browser code (a fetch
// exporter, not an http.request one). Only modules Vite loads are affected:
// our sources and the deps inlined in test.server.deps below.
function browserConditionsForTests(): Plugin {
  return {
    name: 'scarcity:browser-conditions-for-tests',
    enforce: 'post',
    configEnvironment(name, environment) {
      if (name !== 'client') return;
      const conditions = environment.resolve?.conditions ?? [];
      environment.resolve = {
        ...environment.resolve,
        conditions: [...conditions.filter((c) => c !== 'node'), 'browser'],
      };
    },
  };
}

export default defineConfig(({ command, mode }) => {
  const tracing = tracingMode(command, mode);
  // Where spans are POSTed (OTLP/HTTP JSON). Same origin in production: the
  // Gateway routes /otlp/... to the collector.
  const otlpUrl = process.env.SCARCITY_OTLP_URL?.trim() || '/otlp/v1/traces';
  // Dev only: proxy /otlp/... to a local collector, rewriting /otlp/x to /x
  // like the Gateway does (e.g. http://localhost:4318). Unset: no proxy.
  const otlpTarget = process.env.SCARCITY_OTLP_TARGET?.trim();
  return {
    plugins: [react(), ...(mode === 'test' ? [browserConditionsForTests()] : [])],
    define: {
      __SCARCITY_TRACING__: JSON.stringify(tracing),
      __SCARCITY_OTLP_URL__: JSON.stringify(otlpUrl),
      __SCARCITY_VERSION__: JSON.stringify(tracing === 'off' ? 'unknown' : gitSha()),
    },
    server: {
      // Not 8080: that is where the services listen locally.
      port: 5173,
      strictPort: true,
      proxy: {
        // Regex key (tested against path + query): /api, /api/..., /api?..., not /apiary.
        '^/api(/|\\?|$)': {
          target: apiTarget,
          changeOrigin: true,
        },
        ...(otlpTarget
          ? {
              '^/otlp(/|$)': {
                target: otlpTarget,
                changeOrigin: true,
                rewrite: (path: string) => path.replace(/^\/otlp/, '') || '/',
              },
            }
          : {}),
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      server: {
        deps: {
          // Load OpenTelemetry through Vite, not Node's own loader, so
          // browserConditionsForTests() applies to it.
          inline: [/[\\/]@opentelemetry[\\/]/],
        },
      },
    },
  };
});
