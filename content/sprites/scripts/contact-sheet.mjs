// Writes docs/contact-sheet.svg: every variant, every frame, on dark and light ground.
// Render it with `rsvg-convert -z 1 docs/contact-sheet.svg -o docs/contact-sheet.png`.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadManifest, loadSheets, symbolBodies, paint, root } from './lib.mjs';

const CELL = 72;
const LABEL = 120;
const SMALL = 60;
const GROUNDS = [{ name: 'dark', bg: '#16130f', fg: '#d8cfc0' }, { name: 'light', bg: '#d9d2c3', fg: '#2a241d' }];

const manifest = loadManifest();
const bodies = symbolBodies(loadSheets(manifest));

function sprite(variant, id, x, y, px) {
  const inner = [bodies[id], ...variant.overlays.map((o) => bodies[manifest.overlays[o]])].join('');
  return `<g transform="translate(${x},${y}) scale(${px / 32})">${paint(inner, variant.palette)}</g>`;
}

function row(proto, variant, y, ground) {
  const category = manifest.categories[variant.category];
  const frames = Object.entries(category.states).flatMap(([state, s]) => s.frames.map((id) => ({ state, id })));
  const first = category.states[Object.keys(category.states)[0]].frames[0];
  let out = `<text x="8" y="${y + 40}" fill="${ground.fg}" font-family="sans-serif" font-size="13">${proto}</text>`;
  out += sprite(variant, first, LABEL + 8, y + 28, 16) + sprite(variant, first, LABEL + 40, y + 36, 32);
  frames.forEach(({ id }, i) => {
    out += sprite(variant, id, LABEL + SMALL + 40 + i * CELL, y + CELL / 2, 64);
  });
  return out;
}

const protos = Object.entries(manifest.variants);
const width = LABEL + SMALL + 20 + 10 * CELL;
const height = GROUNDS.length * (protos.length * CELL + 30);
let body = '';
GROUNDS.forEach((ground, g) => {
  const top = g * (protos.length * CELL + 30);
  body += `<rect x="0" y="${top}" width="${width}" height="${protos.length * CELL + 30}" fill="${ground.bg}"/>`;
  body += `<text x="8" y="${top + 20}" fill="${ground.fg}" font-family="sans-serif" font-size="12">${ground.name} ground: 16 px, 32 px, then every frame at 64 px (idle, moving, acting, acted-upon)</text>`;
  protos.forEach(([proto, variant], i) => {
    body += row(proto, variant, top + 30 + i * CELL, ground);
  });
});
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">${body}</svg>\n`;
writeFileSync(join(root, 'docs/contact-sheet.svg'), svg);
console.log(`wrote docs/contact-sheet.svg (${width} x ${height})`);
