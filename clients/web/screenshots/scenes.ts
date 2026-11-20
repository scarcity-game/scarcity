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

// The replay on the last entry, where the orbiter has started to walk (its sprite shows moving).
async function walking(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Replay', exact: true }).click();
  await page.getByRole('region', { name: 'Replay controls' }).waitFor();
  for (let i = 0; i < 16; i += 1) await page.getByRole('button', { name: 'Next entry' }).click();
  await page.mouse.move(4, 700); // off the marks, so no hover card
}

// One entry further: the step has moved on part of the way (an action-progress entry).
async function walkingOn(page: Page): Promise<void> {
  await walking(page);
  await page.getByRole('button', { name: 'Next entry' }).click();
  await page.mouse.move(4, 700);
}

// Zooms in at the middle of the map with the wheel, then drags it to pan.
async function zoomMap(page: Page): Promise<void> {
  const box = await page.locator('.room-svg').boundingBox();
  if (box === null) throw new Error('no map to zoom');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, -600);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 - 60, box.y + box.height / 2 - 40, { steps: 5 });
  await page.mouse.up();
  await page.mouse.move(box.x + 4, box.y + box.height - 4); // off the marks, so no hover card
}

async function areaList(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Areas', exact: true }).click();
  await page.getByRole('heading', { level: 1, name: 'Areas' }).waitFor();
  await page.getByText('Depth 3 cellar').waitFor();
}

async function areaListAll(page: Page): Promise<void> {
  await areaList(page);
  await page.getByRole('radio', { name: 'All' }).click();
  await page.getByText('Depth 5 cellar').waitFor();
}

export const scenes: Scene[] = [
  { name: 'area', show: async () => {} },
  { name: 'area-list', show: areaList },
  { name: 'area-list-all', show: areaListAll },
  { name: 'hover', show: (page) => mark(page, HUMANOID).hover() },
  { name: 'selected', show: (page) => mark(page, MOUSE).click() },
  { name: 'zoomed', show: zoomMap },
  { name: 'replay', show: enterReplay },
  { name: 'moving', show: walking },
  { name: 'moving-on', show: walkingOn },
];
