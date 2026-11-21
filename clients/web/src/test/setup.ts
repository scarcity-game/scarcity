import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

// findBy* and waitFor give up after 1 s by default. The first render in a test file
// (React, the router, jsdom layout, the fetch chain) takes longer than that on a loaded
// machine (#269), and a wait that is about to succeed costs nothing extra. Failures
// still fail, just after this long. Keep it under vite.config.ts's testTimeout.
configure({ asyncUtilTimeout: 10000 });

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
});

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
