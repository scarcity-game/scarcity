// Helpers for the draw scripts: each scripts/draw/<category>.mjs builds its
// frames as SVG strings with these and calls sheet() to write the category's
// sheet. The output follows AGENTS.md "Drawing rules", so `npm test` checks it.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { XMLParser } from 'fast-xml-parser';
import { root } from '../lib.mjs';
import { extentOf } from '../svg-rules.mjs';

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

/**
 * A straight bar from `a` to `b` (an arm, a shin, a pole) as an outlined polygon `width` wide, and its angle in
 * degrees (clockwise from right): use the angle for a `hand.*` anchor's third value, so held gear lines up.
 */
export function bar([ax, ay], [bx, by], fill, width = 3.4) {
  const len = Math.hypot(bx - ax, by - ay) || 1;
  const [nx, ny] = [(-(by - ay) / len) * (width / 2), ((bx - ax) / len) * (width / 2)];
  const pts = [[ax + nx, ay + ny], [bx + nx, by + ny], [bx - nx, by - ny], [ax - nx, ay - ny]];
  const deg = (Math.atan2(by - ay, bx - ax) * 180) / Math.PI;
  return { svg: `<polygon points="${pts.map(([x, y]) => `${f(x)},${f(y)}`).join(' ')}" fill="${fill}"/>`, deg };
}

/**
 * Something held in a hand at `hand`, pointing `deg` degrees (clockwise from right): a shaft `length` long
 * (a club, spear, staff or sword blade), with a round `knob` of that radius at the far end (0 for none).
 * Returns the markup and the far end, e.g. to keep it inside the box. Draw it before the hand so the fist
 * covers the grip.
 */
export function held([hx, hy], deg, length, { fill = S.detail, width = 2, knob = 0, knobFill = fill } = {}) {
  const a = (deg * Math.PI) / 180;
  const tip = [hx + length * Math.cos(a), hy + length * Math.sin(a)];
  const shaft = limbs([[[hx, hy], tip]], fill, width);
  const end = knob ? `<circle cx="${f(tip[0])}" cy="${f(tip[1])}" r="${f(knob)}" fill="${knobFill}"/>` : '';
  return { svg: shaft + end, tip };
}

/** Anchors rounded like the drawing: {name: [x, y] or [x, y, deg]}. */
const roundAnchors = (anchors) =>
  Object.fromEntries(Object.entries(anchors).map(([k, v]) => [k, v.map((n) => Number(f(n)))]));

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
    states: Object.fromEntries(Object.entries(states).map(([state, frames]) => {
      const anchors = frames.map((fr) => roundAnchors(fr.anchors ?? {}));
      return [state, {
        frames: frames.map((_, i) => `${name}.${state}.${i}`),
        frameMs: old.states?.[state]?.frameMs ?? FRAME_MS[state],
        ...(anchors.some((a) => Object.keys(a).length) ? { anchors } : {}),
      }];
    })),
  };
  manifest.categories = { ...manifest.categories, [name]: entry };
  writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
}

const LIMIT = 16;
const xml = new XMLParser({ preserveOrder: true, ignoreAttributes: false, attributeNamePrefix: '', trimValues: false });

/** How far a frame's drawing reaches, strokes included, measured as the check measures it. */
function frameExtent(svg, wrap) {
  const tree = xml.parse(`<svg><symbol viewBox="-16 -16 32 32">${wrap(svg)}</symbol></svg>`);
  return extentOf(tree[0].svg.find((n) => n.symbol));
}

/**
 * The one [dx, dy] that brings the union of all frames' extents inside the box (0 on an axis that already fits),
 * rounded up to a tenth so the rounded drawing stays inside. One offset for the whole category keeps the frames
 * aligned (no jitter between them). Throws when the union is wider or taller than the box: shrink the drawing.
 */
export function fitShift(extents) {
  const u = extents.reduce((a, e) => ({
    minX: Math.min(a.minX, e.minX), maxX: Math.max(a.maxX, e.maxX), minY: Math.min(a.minY, e.minY), maxY: Math.max(a.maxY, e.maxY),
  }));
  const axis = (lo, hi, label) => {
    if (hi - lo > 2 * LIMIT) throw new Error(`fit: the frames span ${(hi - lo).toFixed(1)} in ${label}, more than the box's ${2 * LIMIT}; draw smaller`);
    if (lo < -LIMIT) return Math.ceil((-LIMIT - lo) * 10) / 10;
    if (hi > LIMIT) return -Math.ceil((hi - LIMIT) * 10) / 10;
    return 0;
  };
  return [axis(u.minX, u.maxX, 'x'), axis(u.minY, u.maxY, 'y')];
}

/** Every frame moved by fitShift, anchors too. */
function fitStates(name, states, wrap) {
  const [dx, dy] = fitShift(Object.values(states).flat().map(({ svg }) => frameExtent(svg, wrap)));
  if (!dx && !dy) return states;
  console.log(`fit: moved every ${name} frame by (${f(dx)}, ${f(dy)}) to fit the box`);
  const move = (anchors = {}) => Object.fromEntries(Object.entries(anchors)
    .map(([k, [x, y, ...rest]]) => [k, [x + dx, y + dy, ...rest]]));
  return Object.fromEntries(Object.entries(states).map(([state, frames]) => [state, frames.map(({ svg, anchors }) => ({
    svg: `<g transform="translate(${f(dx)} ${f(dy)})">${svg}</g>`,
    ...(anchors ? { anchors: move(anchors) } : {}),
  }))]));
}

/**
 * Writes categories/<name>.svg from {state: [frame, ...]}, and its sprites.json entry (`options.sample`: the
 * category's sample palette). A frame is its inner markup, or `{ svg, anchors }` where `anchors` names points
 * of that frame: `impact` (where the client draws an effect's icon on it; every acted-upon frame needs one),
 * `head`, `hand.main`, `hand.off`, `hip.main`, `back`, as [x, y] or [x, y, deg] (deg clockwise from right).
 * `options.fit`: shift every frame by one offset so all of them fit the -16..16 box (see fitShift).
 */
export function sheet(name, input, { wrap = frame, sample, fit = false } = {}) {
  let states = Object.fromEntries(Object.entries(input).map(([state, frames]) =>
    [state, frames.map((fr) => (typeof fr === 'string' ? { svg: fr } : fr))]));
  if (fit) states = fitStates(name, states, wrap);
  const symbols = Object.entries(states).flatMap(([state, frames]) =>
    frames.map(({ svg }, i) => `<symbol id="${name}.${state}.${i}" viewBox="-16 -16 32 32">${wrap(svg)}</symbol>`));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-16 -16 32 32">\n${symbols.join('\n')}\n</svg>\n`;
  const path = join(root, 'categories', `${name}.svg`);
  writeFileSync(path, svg);
  writeManifestEntry(name, states, sample);
  console.log(`wrote categories/${name}.svg (${svg.length} bytes, ${symbols.length} frames)`);
}
