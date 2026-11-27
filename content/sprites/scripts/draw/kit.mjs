// Helpers for the draw scripts: each scripts/draw/<category>.mjs builds its
// frames as SVG strings with these and calls sheet() to write the category's
// sheet. The output follows AGENTS.md "Drawing rules", so `npm test` checks it.
import { readFileSync, writeFileSync } from 'node:fs';
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

/** The accent hit star of acted-upon frames, centred at (x, y), moved in so it stays inside the box. */
export function star(x0, y0, r = 4.5) {
  const lim = 16 - r - 1;
  const x = Math.max(-lim, Math.min(lim, x0));
  const y = Math.max(-lim, Math.min(lim, y0));
  const pts = Array.from({ length: 10 }, (_, i) => {
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    const k = i % 2 ? r * 0.45 : r;
    return `${f(x + k * Math.cos(a))},${f(y + k * Math.sin(a))}`;
  });
  return `<polygon points="${pts.join(' ')}" fill="${S.accent}"/>`;
}

/** Points turned by `deg` degrees (clockwise, as y points down) about `centre`: SVG transforms may not rotate. */
export function turn(points, deg, [cx, cy] = [0, 0]) {
  const a = (deg * Math.PI) / 180;
  const c = Math.cos(a);
  const s = Math.sin(a);
  return points.map(([x, y]) => [cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c]);
}

/**
 * A pointed oval (a leaf, a wing, an ear, a flame) as a path `d`: from its base at (x, y), `length` long and
 * `width` wide, pointing at `deg` degrees (0 = right, 90 = down, -90 = up).
 */
export function leaf(x, y, length, width, deg) {
  const [tip, left, right] = turn([[length, 0], [length / 2, -width], [length / 2, width]], deg, [0, 0]);
  const p = ([px, py]) => `${f(x + px)} ${f(y + py)}`;
  return `M${p([0, 0])}Q${p(left)} ${p(tip)}Q${p(right)} ${p([0, 0])}Z`;
}

/**
 * A tapering body along a centre line (a snake, a tail, a tentacle, a neck) as a closed path `d`: `points` is
 * the centre line, `widths` the full width at each point (the last may be 0 for a point).
 */
export function tube(points, widths) {
  const side = (sign) => points.map(([x, y], i) => {
    const [ax, ay] = points[Math.max(0, i - 1)];
    const [bx, by] = points[Math.min(points.length - 1, i + 1)];
    const len = Math.hypot(bx - ax, by - ay) || 1;
    const h = (widths[i] / 2) * sign;
    return [x - ((by - ay) / len) * h, y + ((bx - ax) / len) * h];
  });
  const ring = [...side(1), ...side(-1).reverse()];
  return `${ring.map(([x, y], i) => `${i ? 'L' : 'M'}${f(x)} ${f(y)}`).join('')}Z`;
}

const FRAME_MS = { idle: 600, moving: 120, acting: 150, 'acted-upon': 150, static: 0 };

/**
 * Adds or refreshes categories.<name> in sprites.json from the frames drawn: its file, states and frame
 * times; `sample` (a palette) sets the sample and the fallback colour. Other keys and other entries stay.
 */
function writeManifestEntry(name, states, sample) {
  const path = join(root, 'sprites.json');
  const manifest = JSON.parse(readFileSync(path, 'utf8'));
  const old = manifest.categories[name] ?? {};
  const kind = states.static ? 'static' : 'animated';
  const entry = {
    kind,
    file: `categories/${name}.svg`,
    fallbackColor: sample?.body ?? old.fallbackColor ?? '#8a7f72',
    ...(sample ?? old.sample ? { sample: sample ?? old.sample } : {}),
    states: Object.fromEntries(Object.entries(states).map(([state, frames]) => [state, {
      frames: frames.map((_, i) => `${name}.${state}.${i}`),
      frameMs: old.states?.[state]?.frameMs ?? FRAME_MS[state],
    }])),
  };
  manifest.categories = { ...manifest.categories, [name]: entry };
  writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
}

/**
 * Writes categories/<name>.svg from {state: [frame inner markup, ...]}, and its sprites.json entry
 * (`options.sample`: the category's sample palette).
 */
export function sheet(name, states, { wrap = frame, sample } = {}) {
  const symbols = Object.entries(states).flatMap(([state, frames]) =>
    frames.map((inner, i) => `<symbol id="${name}.${state}.${i}" viewBox="-16 -16 32 32">${wrap(inner)}</symbol>`));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-16 -16 32 32">\n${symbols.join('\n')}\n</svg>\n`;
  const path = join(root, 'categories', `${name}.svg`);
  writeFileSync(path, svg);
  writeManifestEntry(name, states, sample);
  console.log(`wrote categories/${name}.svg (${svg.length} bytes, ${symbols.length} frames)`);
}
