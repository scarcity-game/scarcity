import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// In dev, /api is proxied to a locally running service (the auth service by
// default). Override with SCARCITY_API_TARGET, e.g. http://localhost:8081.
// In production the Gateway routes /api/* to the services, not to nginx.
const apiTarget = process.env.SCARCITY_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
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
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
