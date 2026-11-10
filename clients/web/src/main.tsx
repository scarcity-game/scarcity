import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App.tsx';
import { buildTracingConfig } from './tracing/config.ts';
import { RouteTracker } from './tracing/RouteTracker.tsx';
import { startTracing } from './tracing/start.ts';
import './index.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Missing #root element in index.html');
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      {__SCARCITY_TRACING__ !== 'off' && <RouteTracker />}
      <App />
    </BrowserRouter>
  </StrictMode>,
);

// Tracing loads after React has mounted, as a separate chunk, so it never
// delays first paint. Requests made before it is ready (e.g. the session
// restore GET /api/auth/me) are not traced. With SCARCITY_TRACING=off this
// block, and so the SDK chunk, is removed from the build.
if (__SCARCITY_TRACING__ !== 'off') {
  void startTracing(() => import('./tracing/sdk.ts'), buildTracingConfig());
}
