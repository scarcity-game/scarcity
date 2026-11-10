import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ALICE, json, mockApi } from '../test/mockApi.ts';
import { renderAt } from '../test/renderAt.tsx';
import type { TracingConfig } from './config.ts';
import { resetRouteEvents } from './routeEvents.ts';
import type { TracingHandle } from './sdk.ts';
import { startTracing, type TracingSdk } from './start.ts';

const CONFIG: TracingConfig = { mode: 'on', otlpUrl: '/otlp/v1/traces', serviceVersion: 'test' };
const HELLO = 'Hello, world. The dungeon is being dug.';

let handle: TracingHandle | undefined;

afterEach(async () => {
  await handle?.shutdown();
  handle = undefined;
  resetRouteEvents();
});

describe('startTracing', () => {
  const failures: Array<[string, () => Promise<TracingSdk>]> = [
    ['the SDK chunk fails to load', () => Promise.reject(new TypeError('Failed to fetch dynamically imported module'))],
    ['the loader throws synchronously', () => { throw new Error('boom'); }],
    ['initTracing throws', async () => ({ initTracing: () => { throw new Error('init failed'); } })],
    ['initTracing returns nothing', async () => ({ initTracing: () => undefined })],
  ];

  it.each(failures)('leaves the app working when %s', async (_name, load) => {
    const api = mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/');

    await expect(startTracing(load, CONFIG)).resolves.toBeUndefined();

    expect(screen.getByText(HELLO)).toBeInTheDocument();
    const response = await fetch('/api/auth/me');
    expect(response.status).toBe(200);
    expect(api.requests[0]!.headers.get('traceparent')).toBeNull();
  });

  it('starts the real SDK next to a rendered app', async () => {
    mockApi({});
    renderAt('/');

    handle = await startTracing(() => import('./sdk.ts'), CONFIG);

    expect(handle).toBeDefined();
    expect(screen.getByText(HELLO)).toBeInTheDocument();
  });
});
