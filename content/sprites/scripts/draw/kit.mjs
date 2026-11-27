// Helpers for the draw scripts: each scripts/draw/<category>.mjs builds its
// frames as SVG strings with these and calls sheet() to write the category's
// sheet. The output follows AGENTS.md "Drawing rules", so `npm test` checks it.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { root } from '../lib.mjs';

/** The palette slots, with the fallbacks every sheet uses. */
export const S = {
  body: 'var(--sprite-body, #8a7f72)',
  accent: 'var(--sprite-accent, #b9892f)',
  detail: 'var(--sprite-detail, #5c5348)',
  outline: 'var(--sprite-outline, #231d17)',
};

/** A number with at most one decimal and no trailing zeros, to keep sheets small. */
export const f = (n) => String(Math.round(n * 10) / 10);

/** Wraps one frame's shapes in the outlined group every frame has. */
export const frame = (inner) =>
  `<g stroke="${S.outline}" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round">${inner}</g>`;

/**
 * Thick outlined limbs: each limb is a list of [x, y] joints; all of them go in
 * one path, stroked twice (outline wide, then the colour), so eight legs cost
 * two elements.
 */
export function limbs(list, colour, width) {
  const d = list.map((pts) => pts.map(([x, y], i) => `${i ? 'L' : 'M'}${f(x)} ${f(y)}`).join('')).join('');
  // The outline pass inherits the frame group's outline stroke.
  return `<path d="${d}" fill="none" stroke-width="${f(width + 2)}"/>`
    + `<path d="${d}" fill="none" stroke="${colour}" stroke-width="${f(width)}"/>`;
}

/** The accent hit star of acted-upon frames, centred at (x, y). */
export function star(x, y, r = 4.5) {
  const pts = Array.from({ length: 10 }, (_, i) => {
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    const k = i % 2 ? r * 0.45 : r;
    return `${f(x + k * Math.cos(a))},${f(y + k * Math.sin(a))}`;
  });
  return `<polygon points="${pts.join(' ')}" fill="${S.accent}"/>`;
}

/** Writes categories/<name>.svg from {state: [frame inner markup, ...]}. */
export function sheet(name, states, wrap = frame) {
  const symbols = Object.entries(states).flatMap(([state, frames]) =>
    frames.map((inner, i) => `<symbol id="${name}.${state}.${i}" viewBox="-16 -16 32 32">${wrap(inner)}</symbol>`));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-16 -16 32 32">\n${symbols.join('\n')}\n</svg>\n`;
  const path = join(root, 'categories', `${name}.svg`);
  writeFileSync(path, svg);
  console.log(`wrote categories/${name}.svg (${svg.length} bytes, ${symbols.length} frames)`);
}
