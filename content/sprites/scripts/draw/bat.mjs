// Draws categories/bat.svg: `npm run draw -- bat`.
// A bat facing right, hovering: two wide webbed wings (finger bones, scalloped
// hem) either side of a small furred body, a round head with big ears and
// fangs. The wings are the read; flap raises and lowers the tips.
import { sheet, S, f } from './kit.mjs';

/** One wing as [x, y] points in its own frame (x outward from the shoulder, y up is negative). */
const wingPoints = (flap, reach) => {
  const r = reach;
  return {
    tips: [[13 * r, -3.5 - 4.5 * flap], [10 * r, 5 - 3 * flap], [6 * r, 7 - 2 * flap]],
    wrist: [6 * r, -7 - 3 * flap],
    notches: [[10 * r, 0.5 - 3 * flap], [7.5 * r, 3.5 - 2.5 * flap], [3 * r, 4]],
  };
};

/** The wing's web and bones; `sign` is 1 to the right of the body, -1 to the left. */
function wing(ox, oy, sign, flap, reach, fill) {
  const w = wingPoints(flap, reach);
  const P = ([x, y]) => [ox + sign * x, oy + y];
  const ring = [[0, -1], w.wrist, w.tips[0], w.notches[0], w.tips[1], w.notches[1], w.tips[2], w.notches[2], [0, 4]];
  const d = ring.map((p, i) => `${i ? 'L' : 'M'}${P(p).map(f).join(' ')}`).join('') + 'Z';
  const [wx, wy] = P(w.wrist);
  const bones = w.tips.map((t) => `M${f(wx)} ${f(wy)}L${P(t).map(f).join(' ')}`).join('')
    + `M${P([0, -1]).map(f).join(' ')}L${f(wx)} ${f(wy)}`;
  return `<path d="${d}" fill="${fill}"/><path d="${bones}" fill="none" stroke-width="1.5"/>`;
}

function bat({ dx = 0, dy = 0, flap = 0, lean = 0, fangs = 0, curl = 0, fold = 1 }) {
  const bx = dx;
  const by = dy + 2;
  // The near (left in the picture, behind) wing is a little smaller and flaps a little behind.
  const hx = bx + 2.5 + lean;
  const hy = by - 4.5 + curl * 2;
  const svg = [
    wing(bx - 1, by - 1, -1, flap * 0.8, 0.93 * fold, S.detail),
    wing(bx + 1, by - 1, 1, flap, fold, S.detail),
    `<path d="M${f(bx - 3)} ${f(by + 3)}L${f(bx - 5.5)} ${f(by + 8)}L${f(bx - 0.5)} ${f(by + 6.5)}Z" fill="${S.body}"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by + 1)}" rx="3" ry="4" fill="${S.body}"/>`,
    `<path d="M${f(hx - 2.5)} ${f(hy - 1.5)}L${f(hx - 3.5)} ${f(hy - 7.5)}L${f(hx)} ${f(hy - 3.5)}Z`
      + `M${f(hx + 0.5)} ${f(hy - 3.5)}L${f(hx + 3.5)} ${f(hy - 8)}L${f(hx + 4)} ${f(hy - 1)}Z" fill="${S.body}"/>`,
    `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="3.4" ry="3.1" fill="${S.body}"/>`,
    `<g fill="${S.accent}" stroke="none">`
      + `<circle cx="${f(hx + 1)}" cy="${f(hy - 0.8)}" r="1.1"/><circle cx="${f(hx + 3)}" cy="${f(hy - 0.6)}" r="0.9"/>`
      + `<path d="M${f(hx + 1.5)} ${f(hy + 2)}L${f(hx + 2.3)} ${f(hy + 4 + fangs)}L${f(hx + 3.1)} ${f(hy + 2)}Z"/></g>`,
  ].join('');
  return { svg, anchors: { impact: [hx + 1, hy - 8], head: [hx, hy] } };
}

sheet('bat', {
  idle: [bat({ flap: 0.5 }), bat({ flap: -0.4, dy: 0.5 })],
  moving: [
    bat({ flap: 1, dy: -0.5 }),
    bat({ flap: 0.1, dx: 0.5 }),
    bat({ flap: -1, dx: 0.5, dy: 0.5 }),
    bat({ flap: 0.1, dx: 0.5 }),
  ],
  acting: [bat({ flap: 0.8, lean: -1 }), bat({ flap: -0.8, lean: 2, dx: 0.5, curl: -1, fangs: 1.5, dy: 1 })],
  'acted-upon': [bat({ flap: 1.2, dx: -1, lean: -2 }), bat({ flap: -1.4, curl: 2, dy: 1, fold: 0.7 })],
}, {
  slots: {
    body: { paints: 'the furred body, head and ears', large: true },
    accent: { paints: 'the eyes and fang' },
    detail: { paints: 'the wings', large: true },
    outline: { paints: 'the dark edge' },
  },
  sample: { body: '#7a6556', accent: '#c4533e', detail: '#76637a', outline: '#120e0c' },
});
