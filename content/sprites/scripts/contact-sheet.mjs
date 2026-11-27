// Writes docs/contact-sheet.svg: every variant (and each unused category's sample), every frame, on dark and light ground.
// Render it with `rsvg-convert -z 1 docs/contact-sheet.svg -o docs/contact-sheet.png`.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadManifest, loadSheets, symbolBodies, composeFrame, root, shownVariants } from './lib.mjs';

const CELL = 72;
const LABEL = 120;
const SMALL = 60;
const GROUNDS = [{ name: 'dark', bg: '#16130f', fg: '#d8cfc0' }, { name: 'light', bg: '#d9d2c3', fg: '#2a241d' }];

const manifest = loadManifest();
const bodies = symbolBodies(loadSheets(manifest));

/** One frame as the client draws it (overlays at their anchors, the effect icon when acted upon). */
function sprite(variant, state, i, x, y, px, effect) {
  const inner = composeFrame(manifest, bodies, variant.category, state, i, variant, effect);
  return `<g transform="translate(${x},${y}) scale(${px / 32})">${inner}</g>`;
}

function row(proto, variant, y, ground) {
  const category = manifest.categories[variant.category];
  const frames = Object.entries(category.states).flatMap(([state, s]) => s.frames.map((_, i) => ({ state, i })));
  const firstState = Object.keys(category.states)[0];
  let out = `<text x="8" y="${y + 40}" fill="${ground.fg}" font-family="sans-serif" font-size="13">${proto}</text>`;
  out += sprite(variant, firstState, 0, LABEL + 8, y + 28, 16) + sprite(variant, firstState, 0, LABEL + 40, y + 36, 32);
  // The two acted-upon frames show both effect kinds: physical, then magic.
  frames.forEach(({ state, i }, n) => {
    const effect = state === 'acted-upon' && i === 1 ? 'magic' : 'physical';
    out += sprite(variant, state, i, LABEL + SMALL + 40 + n * CELL, y + CELL / 2, 64, effect);
  });
  return out;
}

const protos = shownVariants(manifest);
const width = LABEL + SMALL + 20 + 10 * CELL;
const height = GROUNDS.length * (protos.length * CELL + 30);
let body = '';
GROUNDS.forEach((ground, g) => {
  const top = g * (protos.length * CELL + 30);
  body += `<rect x="0" y="${top}" width="${width}" height="${protos.length * CELL + 30}" fill="${ground.bg}"/>`;
  body += `<text x="8" y="${top + 20}" fill="${ground.fg}" font-family="sans-serif" font-size="12">${ground.name} ground: 16 px, 32 px, then every frame at 64 px (idle, moving, acting, acted-upon: hit by a physical effect, then a magic one)</text>`;
  protos.forEach(([proto, variant], i) => {
    body += row(proto, variant, top + 30 + i * CELL, ground);
  });
});
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">${body}</svg>\n`;
writeFileSync(join(root, 'docs/contact-sheet.svg'), svg);
console.log(`wrote docs/contact-sheet.svg (${width} x ${height})`);
