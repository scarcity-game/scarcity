// Writes docs/sheet-<category>.svg (git-ignored), for drawing one category:
// every frame at 128 px with the 32-unit grid and the radius circle, every frame
// at 16, 32 and 64 px on dark and light ground, and a line-up of every
// category's first frame at 16 and 32 px, filled, then as a flat silhouette
// (outline colour only), to judge whether the new one reads apart.
// `npm run sheet -- <category> [--png]`; --png renders it with rsvg-convert.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadManifest, loadSheets, symbolBodies, paint, root } from './lib.mjs';

const [name, flag] = process.argv.slice(2);
const manifest = loadManifest();
const category = manifest.categories[name];
if (!category) {
  console.error(`usage: npm run sheet -- <category> [--png]; categories: ${Object.keys(manifest.categories).join(', ')}`);
  process.exit(1);
}
const bodies = symbolBodies(loadSheets(manifest));
const GROUNDS = [{ bg: '#16130f', fg: '#d8cfc0' }, { bg: '#d9d2c3', fg: '#2a241d' }];
const DEFAULT = { body: '#8a7f72', accent: '#b9892f', detail: '#5c5348', outline: '#231d17' };

/** A category's palette: its sample, else its first variant's, else the slot fallbacks. */
function paletteOf(cat) {
  const variant = Object.values(manifest.variants).find((v) => v.category === cat);
  return manifest.categories[cat].sample ?? variant?.palette ?? DEFAULT;
}

const frames = Object.entries(category.states).flatMap(([state, s]) => s.frames.map((id) => ({ state, id })));
const palette = paletteOf(name);
const sprite = (id, x, y, px, pal = palette) =>
  `<g transform="translate(${x},${y}) scale(${px / 32})">${paint(bodies[id], pal)}</g>`;
const text = (x, y, fill, s) => `<text x="${x}" y="${y}" fill="${fill}" font-family="sans-serif" font-size="12">${s}</text>`;

function grid(x, y, size) {
  const k = size / 32;
  let out = `<rect x="${x - 16 * k}" y="${y - 16 * k}" width="${size}" height="${size}" fill="none" stroke="#5a5248" stroke-width="1"/>`;
  out += `<circle cx="${x}" cy="${y}" r="${16 * k}" fill="none" stroke="#5a5248" stroke-dasharray="4 4"/>`;
  for (let u = -12; u <= 12; u += 4) {
    out += `<line x1="${x + u * k}" y1="${y - 16 * k}" x2="${x + u * k}" y2="${y + 16 * k}" stroke="#2f2a24" stroke-width="${u === 0 ? 1 : 0.5}"/>`;
    out += `<line x1="${x - 16 * k}" y1="${y + u * k}" x2="${x + 16 * k}" y2="${y + u * k}" stroke="#2f2a24" stroke-width="${u === 0 ? 1 : 0.5}"/>`;
  }
  return out;
}

const BIG = 128;
const COL = BIG + 16;
const width = Math.max(frames.length * COL + 16, 900);
let y = 0;
let body = `<rect width="${width}" height="100%" fill="${GROUNDS[0].bg}"/>`;

// Frames, large, on the grid.
body += text(8, 18, GROUNDS[0].fg, `${name}: every frame at ${BIG} px; the box is the 32-unit grid, the dashed circle the radius`);
frames.forEach(({ state, id }, i) => {
  const cx = 8 + BIG / 2 + i * COL;
  body += grid(cx, 30 + BIG / 2, BIG) + sprite(id, cx, 30 + BIG / 2, BIG);
  body += text(cx - BIG / 2, 30 + BIG + 16, GROUNDS[0].fg, id.slice(name.length + 1));
});
y = 30 + BIG + 30;

// Frames at play sizes, dark and light.
for (const ground of GROUNDS) {
  body += `<rect x="0" y="${y}" width="${width}" height="96" fill="${ground.bg}"/>`;
  frames.forEach(({ id }, i) => {
    const x = 8 + i * COL;
    body += sprite(id, x + 8, y + 48, 16) + sprite(id, x + 36, y + 48, 32) + sprite(id, x + 100, y + 48, 64);
  });
  y += 96;
}

// Line-up of every category: first frame, filled then silhouette, at 32 and 16 px.
const cats = Object.keys(manifest.categories);
const first = (cat) => {
  const states = manifest.categories[cat].states;
  return (states.idle ?? states.static ?? Object.values(states)[0]).frames[0];
};
const silhouette = () => Object.fromEntries(Object.keys(DEFAULT).map((k) => [k, GROUNDS[1].fg]));
for (const [label, ground, pal] of [['filled', GROUNDS[0], paletteOf], ['silhouette', GROUNDS[1], silhouette]]) {
  body += `<rect x="0" y="${y}" width="${width}" height="110" fill="${ground.bg}"/>`;
  body += text(8, y + 16, ground.fg, `every category, ${label}, at 32 and 16 px (${name} is underlined)`);
  cats.forEach((cat, i) => {
    const x = 24 + i * 64;
    body += sprite(first(cat), x + 8, y + 48, 32, pal(cat)) + sprite(first(cat), x + 8, y + 80, 16, pal(cat));
    body += `<text x="${x - 16}" y="${y + 104}" fill="${ground.fg}" font-family="sans-serif" font-size="10"${cat === name ? ' text-decoration="underline"' : ''}>${cat}</text>`;
  });
  y += 110;
}

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${y}" viewBox="0 0 ${width} ${y}">${body}</svg>\n`;
const out = join(root, `docs/sheet-${name}.svg`);
writeFileSync(out, svg);
if (flag === '--png') {
  execFileSync('rsvg-convert', [out, '-o', out.replace(/\.svg$/, '.png')]);
  console.log(`wrote ${out.replace(/\.svg$/, '.png')}`);
} else {
  console.log(`wrote ${out}`);
}
