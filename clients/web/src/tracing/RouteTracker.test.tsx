import { fireEvent, render, screen, within } from '@testing-library/react';
import { InMemorySpanExporter } from '@opentelemetry/sdk-trace';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App.tsx';
import { mockApi } from '../test/mockApi.ts';
import { resetRouteEvents } from './routeEvents.ts';
import { routePattern, RouteTracker } from './RouteTracker.tsx';
import { initTracing, type TracingHandle } from './sdk.ts';

let handle: TracingHandle | undefined;

afterEach(async () => {
  await handle?.shutdown();
  handle = undefined;
  resetRouteEvents();
});

describe('routePattern', () => {
  it.each([
    ['/', '/'],
    ['/login', '/login'],
    ['/register', '/register'],
    ['/play', '/play'],
    ['/play/', '/play'],
    ['/users/alice/secret-token', '/*'],
    ['/nowhere', '/*'],
  ])('%s -> %s', (pathname, pattern) => {
    expect(routePattern(pathname)).toBe(pattern);
  });
});

describe('RouteTracker', () => {
  it('records a span per route change, with the pattern and no query string', async () => {
    mockApi({});
    const exporter = new InMemorySpanExporter();
    render(
      <MemoryRouter initialEntries={['/nowhere/alice?next=%2Fsecret']}>
        <RouteTracker />
        <App />
      </MemoryRouter>,
    );
    // The first route is recorded before tracing starts and replayed.
    handle = initTracing(
      { mode: 'on', otlpUrl: '/otlp/v1/traces', serviceVersion: 'test' },
      { exporter, simpleProcessor: true },
    );

    fireEvent.click(screen.getByRole('link', { name: 'Back to the start' }));
    fireEvent.click(within(screen.getByRole('banner')).getByRole('link', { name: 'Log in' }));

    await vi.waitFor(() => expect(exporter.getFinishedSpans()).toHaveLength(3));
    const spans = exporter.getFinishedSpans();
    expect(spans.map((span) => span.name)).toEqual(['navigate /*', 'navigate /', 'navigate /login']);
    expect(JSON.stringify(spans.map((s) => [s.name, s.attributes]))).not.toMatch(/alice|secret|next/);
  });
});
