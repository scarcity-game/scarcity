// The scenes. To add one, add an entry: a name and a `show` function that
// brings the page into the state to photograph (the area view is already open).
import type { Page } from '@playwright/test';
import { AREA, BEDROCK_WALL, DIVE_STAIRS, FLOOR_CELL, HUMANOID, MOUSE, STAIRS, WALL } from './fixtures.ts';

export interface Scene {
  /** The page to open (default: the test room), and the heading it shows. */
  path?: string;
  title?: string;
  name: string;
  show: (page: Page) => Promise<void>;
}

export const AREA_PATH = `/games/00000000-0000-4000-8000-000000000001/areas/${AREA.slice('area:'.length)}`;
const DIVE_PATH = '/games/00000000-0000-4000-8000-000000000001/areas/00000000-0000-4000-8000-000000000300';
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

// Selects the orbiter and follows it: the map centres on it.
async function follow(page: Page): Promise<void> {
  await mark(page, HUMANOID).click();
  await page.getByRole('button', { name: 'Follow', exact: true }).click();
  await page.mouse.move(4, 700); // off the marks, so no hover card
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

// The game page with several areas and nobody found to follow: the filtered list, Active selected.
async function gamePage(page: Page): Promise<void> {
  await page.goto(new URL(AREA_PATH.replace(/\/areas\/.*$/, ''), page.url()).href);
  await page.getByText('Depth 3 cellar').waitFor();
}

// The game page of a game with one area: the list holding it, no redirect.
async function soloGamePage(page: Page): Promise<void> {
  await page.goto(new URL('/games/00000000-0000-4000-8000-000000000002', page.url()).href);
  await page.getByRole('link', { name: 'Test room' }).waitFor();
}

// Hovers the first log entry that names an entity: its mark gets the dashed ring.
async function hoverLog(page: Page): Promise<void> {
  await page.locator('.log-entry .log-name').first().hover();
}

// The replay on the entry where the walking orbiter was stopped: its sprite stands where it was stopped, the log gives the cause.
async function interrupted(page: Page): Promise<void> {
  await walkingOn(page);
  for (let i = 0; i < 2; i += 1) await page.getByRole('button', { name: 'Next entry' }).click();
  await page.locator('.log-current').scrollIntoViewIfNeeded(); // the log is below the fold when narrow
  await page.mouse.move(4, 700); // off the marks, so no hover card
}

// The "Floor cells" switch on: the floor plan's outline behind the walls.
async function floorCells(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Floor cells' }).click();
  await page.mouse.move(4, 700); // off the marks
}

// A floor cell picked on the map: the inspector shows its kind, neighbours, clearance and widths.
async function floorCellSelected(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Floor cells' }).click();
  await page.locator(`[data-cell-id="${FLOOR_CELL}"]`).dispatchEvent('click');
  await page.mouse.move(4, 700);
}

// Stairs picked on the map: the inspector shows where they lead, with the way there (scrolled into view when it is below the fold).
async function stairsSelected(page: Page, id: string, way: 'link' | 'none'): Promise<void> {
  await mark(page, id).click();
  const target = way === 'link' ? page.getByRole('link', { name: /^Go to / }) : page.getByText(/No level there yet/);
  await target.scrollIntoViewIfNeeded();
  await page.mouse.move(4, 700);
}

// A press on the mouse's centre held for half a second, so its sprite changes frame inside the click (#393).
async function pressMouseCentre(page: Page): Promise<void> {
  const box = await mark(page, MOUSE).boundingBox();
  if (box === null) throw new Error('no mouse to press');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 8 });
  await page.mouse.down();
  await page.waitForTimeout(500);
  await page.mouse.up();
}

export const scenes: Scene[] = [
  { name: 'area', show: async () => {} },
  { name: 'area-list', show: areaList },
  { name: 'game-areas', show: gamePage },
  { name: 'game-one-area', show: soloGamePage },
  { name: 'area-list-all', show: areaListAll },
  { name: 'hover', show: (page) => mark(page, HUMANOID).hover() },
  { name: 'log-hover', show: hoverLog },
  { name: 'selected', show: (page) => mark(page, MOUSE).click() },
  { name: 'select-mouse-centre', show: pressMouseCentre },
  { name: 'stairs-selected', show: (page) => stairsSelected(page, STAIRS, 'link') },
  { name: 'stairs-no-level', path: DIVE_PATH, title: 'Dive level 2', show: (page) => stairsSelected(page, DIVE_STAIRS, 'none') },
  { name: 'wall-selected', show: (page) => mark(page, WALL).click() },
  { name: 'bedrock-wall-selected', show: (page) => mark(page, BEDROCK_WALL).click() },
  { name: 'floor-cells', show: floorCells },
  { name: 'floor-cell-selected', show: floorCellSelected },
  { name: 'dive', path: DIVE_PATH, title: 'Dive level 2', show: async () => {} },
  { name: 'following', show: follow },
  { name: 'zoomed', show: zoomMap },
  { name: 'replay', show: enterReplay },
  { name: 'moving', show: walking },
  { name: 'moving-on', show: walkingOn },
  { name: 'interrupted', show: interrupted },
];
