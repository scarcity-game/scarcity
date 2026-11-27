// Draws categories/spectre.svg: `npm run draw -- spectre`.
// A hooded robe floating facing right: pointed hood with a dark face and two
// eyes, a bell of robe tapering down to a ragged hem with clear air under it,
// no legs and no hands, long sleeves (one in front, one behind).
import { sheet, S, f, bar } from './kit.mjs';

const TEETH = 4;

/** The robe, hood to hem, as one path so no seam shows; `phase` ripples the hem teeth. */
function robe({ x, y, lean, phase, flare }) {
  const tip = [x + 1 + lean, y - 14];
  const hem = [];
  const step = (20 + flare * 2) / TEETH;
  for (let i = 0; i <= TEETH; i++) {
    const hx = x - 10 - flare + step * i;
    const wave = Math.sin(phase + i * 1.7);
    hem.push(`L${f(hx + lean * 0.5)} ${f(y + 7 + wave * 1.6)}`);
    if (i < TEETH) {
      const mx = hx + step / 2;
      hem.push(`L${f(mx + lean * 0.5)} ${f(y + 10.5 + Math.sin(phase + i * 1.7 + 1) * 2.2)}`);
    }
  }
  return `M${f(tip[0])} ${f(tip[1])}C${f(x - 3 + lean)} ${f(y - 14)} ${f(x - 5 + lean)} ${f(y - 9)} ${f(x - 5 + lean)} ${f(y - 5)}`
    + `C${f(x - 8 - flare)} ${f(y - 1)} ${f(x - 10 - flare)} ${f(y + 4)} ${f(x - 10 - flare)} ${f(y + 8)}`
    + hem.join('')
    + `C${f(x + 10 + flare)} ${f(y + 4)} ${f(x + 8 + flare)} ${f(y - 1)} ${f(x + 7 + lean)} ${f(y - 5)}`
    + `C${f(x + 7 + lean)} ${f(y - 9)} ${f(x + 5 + lean)} ${f(y - 14)} ${f(tip[0])} ${f(tip[1])}Z`;
}

function spectre({ dx = 0, dy = 0, lean = 0, phase = 0, reach = 0, flare = 0, droop = 0, cower = 0 }) {
  const x = dx;
  const y = dy + 0.5 + cower * 0.6;
  const head = [x + 2 + lean, y - 8 + cower * 1.5];
  // Near sleeve: shoulder to a wide open cuff; `reach` swings it out and up, `droop` lets it hang.
  const shoulder = [x + 3 + lean, y - 3];
  const cuff = [x + 8 + reach * 3 + lean, y + 3 - reach * 3 - droop * 2];
  const far = bar([x - 4 + lean, y - 3], [x - 9 - flare - droop, y + 4 + droop], S.body, 3.6);
  const near = bar(shoulder, cuff, S.body, 4);
  const svg = [
    far.svg,
    `<path d="${robe({ x, y, lean, phase, flare })}" fill="${S.body}"/>`,
    near.svg,
    `<circle cx="${f(cuff[0])}" cy="${f(cuff[1])}" r="2.3" fill="${S.body}"/>`,
    `<ellipse cx="${f(head[0])}" cy="${f(head[1])}" rx="3.6" ry="4" fill="${S.detail}" stroke="none"/>`,
    `<g fill="${S.accent}" stroke="none"><circle cx="${f(head[0] + 1.6)}" cy="${f(head[1] - 0.6)}" r="1.1"/>`
      + `<circle cx="${f(head[0] - 1)}" cy="${f(head[1] - 0.8)}" r="1"/></g>`,
  ].join('');
  return { svg, anchors: { head, impact: [head[0], y - 15] } };
}

sheet('spectre', {
  idle: [spectre({ phase: 0 }), spectre({ phase: 2.6, dy: -0.8, droop: 1, lean: -1, flare: 0.5 })],
  moving: [
    spectre({ lean: 1.5, phase: 0, dx: -0.5 }),
    spectre({ lean: 2.5, phase: 1.6, dy: -0.8, dx: 0.5 }),
    spectre({ lean: 1.5, phase: 3.2, dx: 1 }),
    spectre({ lean: 2.5, phase: 4.8, dy: -0.8, dx: 0.5 }),
  ],
  acting: [spectre({ reach: 0.8, lean: 0.5, phase: 1, dx: -1 }), spectre({ reach: 1.3, lean: 1.5, phase: 2, dx: -1, flare: 1 })],
  // Struck: knocked back with the hem flung out, then hunched and shrunk in on itself.
  'acted-upon': [spectre({ dx: -2, lean: -2.5, flare: 1.5, phase: 3, droop: 1 }), spectre({ cower: 2, droop: 2, phase: 5, flare: -1 })],
}, { sample: { body: '#8c94a0', accent: '#5fd0c0', detail: '#262a30', outline: '#101216' } });
