// `node scripts/extent.mjs <category>` (run by `npm run draw`): one line per frame with how far its drawing
// reaches (strokes included) and the room left to each edge of the -16..16 box, so a pose can be fitted in one
// round instead of guessing from the first offender, and each frame's `impact` anchor. A negative room is
// over the edge. The anchor must be inside the box; the effect icon centred on it may stick out (the client
// doesn't clip it), so leave no extra headroom for the icon.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { XMLParser } from 'fast-xml-parser';
import { loadManifest, root, anchorsOf, contrast, DARK_GROUND, MIN_SAMPLE_CONTRAST } from './lib.mjs';
import { extentOf } from './svg-rules.mjs';

const LIMIT = 16;
const parser = new XMLParser({ preserveOrder: true, ignoreAttributes: false, attributeNamePrefix: '', trimValues: false });

/** {id: symbol node} of one sheet. */
function symbols(file) {
  const svg = parser.parse(readFileSync(join(root, file), 'utf8')).find((n) => n.svg).svg;
  return Object.fromEntries(svg.filter((n) => n.symbol).map((n) => [n[':@'].id, n]));
}

const name = process.argv[2];
const manifest = loadManifest();
const category = manifest.categories[name];
if (!category) {
  console.error(`usage: node scripts/extent.mjs <category>; no category "${name}" in sprites.json`);
  process.exit(1);
}
const frames = symbols(category.file);
// The widest reach of any effect icon from its centre, for the header.
const icon = Math.max(...Object.values(symbols('effects.svg')).map((s) => {
  const e = extentOf(s);
  return Math.max(-e.minX, e.maxX, -e.minY, e.maxY);
}));

const r = (v) => v.toFixed(1).padStart(5);
console.log(`${name}: room to each edge of the box (negative = over); the effect icon reaches ${icon.toFixed(1)} around impact and may stick out`);
console.log('frame              x range       y range        left right   top bottom  impact');
for (const [state, { frames: ids }] of Object.entries(category.states)) {
  ids.forEach((id, i) => {
    const e = extentOf(frames[id]);
    const room = [e.minX + LIMIT, LIMIT - e.maxX, e.minY + LIMIT, LIMIT - e.maxY];
    const impact = anchorsOf(category, state, i).impact;
    const iconNote = impact ? `(${impact[0]}, ${impact[1]})` : '';
    const flag = Math.min(...room) < 0 ? '  <- over' : '';
    console.log(`${`${state}.${i}`.padEnd(18)} ${`${e.minX.toFixed(1)}..${e.maxX.toFixed(1)}`.padEnd(13)} `
      + `${`${e.minY.toFixed(1)}..${e.maxY.toFixed(1)}`.padEnd(13)} ${room.map(r).join(' ')}  ${iconNote}${flag}`);
  });
}

// Every sample slot against the dark ground. Only the body is checked (npm test); a dark accent or detail is
// fine for small marks (stripes, an eye), but a slot that fills wings, legs or a mane vanishes on dark ground.
const sample = category.sample ?? {};
const slots = ['body', 'accent', 'detail'].filter((slot) => /^#[0-9a-f]{6}$/i.test(sample[slot] ?? ''));
if (slots.length) {
  console.log(`sample contrast with the dark ground ${DARK_GROUND} (need ${MIN_SAMPLE_CONTRAST} for any slot that fills a large area):`);
  for (const slot of slots) {
    const c = contrast(sample[slot], DARK_GROUND);
    const note = c < MIN_SAMPLE_CONTRAST ? (slot === 'body' ? '  <- too dark (npm test fails)' : '  <- too dark if it fills a large area') : '';
    console.log(`  ${slot.padEnd(7)}${sample[slot]}  ${c.toFixed(2)}${note}`);
  }
}
