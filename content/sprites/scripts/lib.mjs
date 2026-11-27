// Shared helpers: load the manifest and the symbols of every sheet.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = join(dirname(fileURLToPath(import.meta.url)), '..');

export const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));

export function loadManifest() {
  return readJson(join(root, 'sprites.json'));
}

/** Raw text of each sheet, by manifest file path. */
export function loadSheets(manifest) {
  const files = [...Object.values(manifest.categories).map((c) => c.file), 'overlays.svg', 'effects.svg',
    ...Object.values(manifest.patterns ?? {}).map((p) => p.file)];
  return Object.fromEntries(files.map((file) => [file, readFileSync(join(root, file), 'utf8')]));
}

/** Map symbol id to its inner markup, read from the raw sheet texts. */
export function symbolBodies(sheets) {
  const bodies = {};
  for (const text of Object.values(sheets)) {
    for (const match of text.matchAll(/<symbol id="([^"]+)"[^>]*>([\s\S]*?)<\/symbol>/g)) {
      bodies[match[1]] = match[2];
    }
  }
  return bodies;
}

/** Replace each var(--sprite-<slot>, fallback) with the variant's colour. */
export function paint(markup, palette) {
  return markup.replace(/var\(--sprite-(\w+),\s*#[0-9a-fA-F]+\)/g, (_, slot) => palette[slot]);
}

/**
 * What the preview and the contact sheet show: every variant, then a
 * "<category> (sample)" row in its sample palette for each category no variant uses yet.
 */
export function shownVariants(manifest) {
  const used = new Set(Object.values(manifest.variants).map((v) => v.category));
  const samples = Object.entries(manifest.categories)
    .filter(([name, c]) => c.sample && !used.has(name))
    .map(([name, c]) => [`${name} (sample)`, { category: name, palette: c.sample, overlays: [] }]);
  return [...Object.entries(manifest.variants), ...samples];
}

/** A frame's anchors, {name: [x, y, deg?]}: the `i`th of its state's `anchors`, or none. */
export function anchorsOf(category, state, i) {
  return category.states[state]?.anchors?.[i] ?? {};
}

/** An overlay entry's symbol id (an entry is the id, or {symbol, anchor, at}). */
export const overlaySymbol = (overlay) => (typeof overlay === 'string' ? overlay : overlay.symbol);

/**
 * The SVG transform that puts an overlay on a frame: none for an unanchored one; for an anchored one, moved
 * from its `at` to the frame's anchor and turned by the difference of their angles; null when the frame
 * lacks the anchor (the overlay is not drawn). The client does the same (clients/web/src/sprites).
 */
export function overlayTransform(overlay, anchors) {
  if (typeof overlay === 'string') return '';
  const a = anchors[overlay.anchor];
  if (!a) return null;
  const deg = (a[2] ?? 0) - (overlay.at[2] ?? 0);
  return `translate(${a[0]} ${a[1]})${deg ? ` rotate(${deg})` : ''} translate(${-overlay.at[0]} ${-overlay.at[1]})`;
}

/** The effect kind ({symbol, palette}) the client draws for an effect type. */
export function effectKind(manifest, type) {
  const { kinds, types } = manifest.effects;
  return kinds[types[type] ?? manifest.effects.default];
}

/**
 * One frame as the client draws it, as SVG markup on the 32-unit grid, painted: the frame, the variant's
 * overlays at their anchors, and for an acted-upon frame the icon of `effect` (an effect kind's name) at
 * its `impact` anchor.
 */
export function composeFrame(manifest, bodies, categoryName, state, i, variant, effect = manifest.effects.default) {
  const category = manifest.categories[categoryName];
  const anchors = anchorsOf(category, state, i);
  let out = paint(bodies[category.states[state].frames[i]], variant.palette);
  for (const name of variant.overlays) {
    const overlay = manifest.overlays[name];
    const t = overlayTransform(overlay, anchors);
    if (t !== null) out += `<g${t ? ` transform="${t}"` : ''}>${paint(bodies[overlaySymbol(overlay)], variant.palette)}</g>`;
  }
  if (state === 'acted-upon') {
    const kind = manifest.effects.kinds[effect];
    const [x, y] = anchors.impact ?? [0, -8];
    out += `<g transform="translate(${x} ${y})">${paint(bodies[kind.symbol], kind.palette)}</g>`;
  }
  return out;
}

/** The play view's dark ground, and the contrast a category's sample body needs against it. */
export const DARK_GROUND = '#16130f';
export const MIN_SAMPLE_CONTRAST = 2.4;

/** WCAG contrast ratio of two #rrggbb colours. */
export function contrast(a, b) {
  const lum = (hex) => {
    const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
