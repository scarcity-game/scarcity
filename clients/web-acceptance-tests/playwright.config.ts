import { defineConfig, devices } from '@playwright/test';

// The gateway of the compose stack (stack/compose.yaml). scripts/run.sh sets
// E2E_BASE_URL; for `npm run test:only` against a stack you started yourself,
// it defaults to the stack's default port.
const port = process.env.E2E_PORT || '18473';
const baseURL = process.env.E2E_BASE_URL || `http://127.0.0.1:${port}`;
const ci = !!process.env.CI;

export default defineConfig({
  testDir: './tests',
  // Traces, screenshots and videos of failed tests. scripts/run.sh also writes
  // stack.log here when the run fails. Playwright empties it at the start of a run.
  outputDir: './test-results',
  fullyParallel: true,
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  workers: ci ? 2 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: ci
    ? [['github'], ['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]]
    : [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
