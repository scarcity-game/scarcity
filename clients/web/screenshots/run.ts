// Takes screenshots of the client against a mocked backend. See AGENTS.md,
// "Screenshots". Usage: npm run screenshots -- [--only a,b] [--out dir] [--app dir] [--full-page]
import { chromium, type Browser, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer, type ViteDevServer } from 'vite';
import { mockBackend } from './mock.ts';
import { AREA_PATH, scenes, type Scene } from './scenes.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const SIZES = { wide: { width: 1440, height: 900 }, narrow: { width: 390, height: 844 } };

function option(name: string): string | undefined {
  const at = process.argv.indexOf(`--${name}`);
  return at < 0 ? undefined : process.argv[at + 1];
}

async function startVite(app: string): Promise<ViteDevServer> {
  const server = await createServer({
    root: app,
    logLevel: 'error',
    server: { port: 5199, strictPort: false, proxy: {}, hmr: false },
  });
  await server.listen();
  return server;
}

async function openArea(browser: Browser, base: string, scene: Scene, size: { width: number; height: number }): Promise<Page> {
  const context = await browser.newContext({ viewport: size });
  const page = await context.newPage();
  const unmatched = await mockBackend(context, page);
  page.on('pageerror', (e) => console.error(`  page error: ${e.message}`));
  await page.goto(base + (scene.path ?? AREA_PATH));
  await page.getByRole('heading', { level: 1, name: scene.title ?? 'Test room' }).waitFor();
  await page.locator('[data-entity-id]').first().waitFor();
  await page.waitForLoadState('networkidle');
  if (unmatched.length > 0) console.warn(`  unmocked: ${unmatched.join(', ')}`);
  return page;
}

async function shoot(browser: Browser, base: string, scene: Scene, out: string): Promise<void> {
  for (const [label, size] of Object.entries(SIZES)) {
    const page = await openArea(browser, base, scene, size);
    await scene.show(page);
    await page.waitForTimeout(400);
    const file = path.join(out, `${scene.name}-${label}.png`);
    await page.screenshot({ path: file, fullPage: process.argv.includes('--full-page') });
    console.log(`  ${file}`);
    await page.context().close();
  }
}

async function main(): Promise<void> {
  const only = option('only')?.split(',');
  const out = path.resolve(option('out') ?? path.join(here, 'out'));
  const app = path.resolve(option('app') ?? path.join(here, '..'));
  mkdirSync(out, { recursive: true });
  const server = await startVite(app);
  const browser = await chromium.launch();
  try {
    const base = server.resolvedUrls!.local[0]!.replace(/\/$/, '');
    for (const scene of scenes.filter((s) => !only || only.includes(s.name))) await shoot(browser, base, scene, out);
  } finally {
    await browser.close();
    await server.close();
  }
}

await main();
