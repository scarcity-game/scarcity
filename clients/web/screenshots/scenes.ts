// The scenes. To add one, add an entry: a name and a `show` function that
// brings the page into the state to photograph (the area view is already open).
import type { Page } from '@playwright/test';
import { AREA, HUMANOID, MOUSE } from './fixtures.ts';

export interface Scene {
  name: string;
  show: (page: Page) => Promise<void>;
}

export const AREA_PATH = `/games/00000000-0000-4000-8000-000000000001/areas/${AREA.slice('area:'.length)}`;
const mark = (page: Page, id: string) => page.locator(`[data-entity-id="${id}"]`);

async function enterReplay(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Replay', exact: true }).click();
  await page.getByRole('region', { name: 'Replay controls' }).waitFor();
  await page.getByRole('button', { name: 'Next action' }).click();
  for (let i = 0; i < 4; i += 1) await page.getByRole('button', { name: 'Next entry' }).click();
}

async function zoomMap(page: Page): Promise<void> {
  const box = await page.locator('.room-svg').boundingBox();
  if (box === null) throw new Error('no map to zoom');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, -600);
}

export const scenes: Scene[] = [
  { name: 'area', show: async () => {} },
  { name: 'hover', show: (page) => mark(page, HUMANOID).hover() },
  { name: 'selected', show: (page) => mark(page, MOUSE).click() },
  { name: 'zoomed', show: zoomMap },
  { name: 'replay', show: enterReplay },
];
