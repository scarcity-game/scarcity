// Draws effects.svg: `node scripts/draw/effects.mjs`. One symbol per effect kind, centred on the origin,
// which the client puts on the `impact` anchor of the frame of the entity acted upon. They are coloured by
// the kind's own palette (sprites.json `effects.kinds`), never the creature's, so they read on any sprite.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { root } from '../lib.mjs';
import { S, f, frame, turn } from './kit.mjs';

/** A spiky burst: `n` points between radius r and r * inner. */
function burst(n, r, inner, deg = -90) {
  const pts = Array.from({ length: n * 2 }, (_, i) => [i % 2 ? r * inner : r, 0]).map(([k], i) =>
    turn([[k, 0]], deg + (i * 180) / n)[0]);
  return pts.map(([x, y]) => `${f(x)},${f(y)}`).join(' ');
}

// Physical: a hard, jagged hit burst with a bright core.
const physical = frame(
  `<polygon points="${burst(6, 6, 0.42)}" fill="${S.body}"/>`
  + `<polygon points="${burst(6, 2.8, 0.5, -60)}" fill="${S.accent}" stroke="none"/>`,
);

// Magic: a four-pointed sparkle in a thin ring, with a dot on the ring.
const magic = frame(
  `<circle cx="0" cy="0" r="5.5" fill="none" stroke="${S.detail}" stroke-width="1.5"/>`
  + `<polygon points="${burst(4, 7, 0.28)}" fill="${S.body}"/>`
  + `<circle cx="0" cy="0" r="1.6" fill="${S.accent}" stroke="none"/>`
  + `<circle cx="3.9" cy="-3.9" r="1.2" fill="${S.accent}"/>`,
);

const symbols = { physical, magic };
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-16 -16 32 32">\n${Object.entries(symbols)
  .map(([kind, inner]) => `<symbol id="effect.${kind}" viewBox="-16 -16 32 32">${inner}</symbol>`).join('\n')}\n</svg>\n`;
writeFileSync(join(root, 'effects.svg'), svg);
console.log(`wrote effects.svg (${svg.length} bytes, ${Object.keys(symbols).length} kinds)`);
