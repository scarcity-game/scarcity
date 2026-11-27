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
  const files = [...Object.values(manifest.categories).map((c) => c.file), 'overlays.svg',
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
