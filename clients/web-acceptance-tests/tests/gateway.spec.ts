import { expect, test } from '@playwright/test';

// Smoke checks that the stack routes like the production HTTPRoute
// (k8s-resources/platform/httproute.yaml). No browser needed.
test.describe('gateway routing', () => {
  test('/api/auth/... reaches auth', async ({ request }) => {
    const response = await request.get('/api/auth/.well-known/jwks.json');
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.keys).toHaveLength(1);
    expect(body.keys[0]).toMatchObject({ kty: 'RSA', alg: 'RS256' });
  });

  test('PathPrefix matches whole segments: /api/authx is not routed to auth', async ({ request }) => {
    // Falls through to the client, whose nginx 404s /api/* instead of serving HTML.
    const response = await request.get('/api/authx');
    expect(response.status()).toBe(404);
    expect(response.headers()['content-type'] ?? '').not.toContain('json');
  });

  test('/ serves the SPA, including client-side routes', async ({ request }) => {
    for (const path of ['/', '/login', '/play']) {
      const response = await request.get(path);
      expect(response.status(), path).toBe(200);
      expect(await response.text(), path).toContain('<div id="root"');
    }
  });
});
