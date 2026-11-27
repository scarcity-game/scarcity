// Draws categories/bat.svg: `npm run draw -- bat`.
// A bat facing right, hovering: two wide webbed wings (finger bones, scalloped
// hem) either side of a small furred body, a round head with big ears and
// fangs. The wings are the read; flap raises and lowers the tips.
import { sheet, S, f } from './kit.mjs';

/** One wing as [x, y] points in its own frame (x outward from the shoulder, y up is negative). */
const wingPoints = (flap, reach) => {
  const r = reach;
  return {
    tips: [[13.5 * r, 0 - 6 * flap], [10.5 * r, 6.5 - 4 * flap], [6.5 * r, 8.5 - 2 * flap]],
    wrist: [6 * r, -8 - 5 * flap],
    notches: [[10.5 * r, 1.5 - 3.5 * flap], [8 * r, 3.5 - 2.5 * flap], [3.5 * r, 5]],
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
  const hx = bx + 3 + lean;
  const hy = by - 6 + curl * 2;
  const svg = [
    wing(bx - 1.5, by - 1, -1, flap * 0.8, 0.93 * fold, S.detail),
    wing(bx + 1, by - 1, 1, flap, fold, S.detail),
    `<path d="M${f(bx - 4)} ${f(by + 5)}L${f(bx - 7)} ${f(by + 10)}L${f(bx - 1)} ${f(by + 8)}Z" fill="${S.body}"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by + 1)}" rx="4" ry="5.5" fill="${S.body}"/>`,
    `<path d="M${f(hx - 3)} ${f(hy - 2)}L${f(hx - 4.5)} ${f(hy - 9)}L${f(hx)} ${f(hy - 4)}Z`
      + `M${f(hx + 1)} ${f(hy - 4)}L${f(hx + 4.5)} ${f(hy - 9.5)}L${f(hx + 5)} ${f(hy - 1)}Z" fill="${S.body}"/>`,
    `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="4.5" ry="4" fill="${S.body}"/>`,
    `<g fill="${S.accent}" stroke="none">`
      + `<circle cx="${f(hx + 1.5)}" cy="${f(hy - 1)}" r="1.2"/><circle cx="${f(hx + 4)}" cy="${f(hy - 0.8)}" r="1"/>`
      + `<path d="M${f(hx + 2)} ${f(hy + 2.5)}L${f(hx + 2.8)} ${f(hy + 4.5 + fangs)}L${f(hx + 3.6)} ${f(hy + 2.5)}Z"/></g>`,
  ].join('');
  return { svg, anchors: { impact: [hx + 1, hy - 9], head: [hx, hy] } };
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
}, { sample: { body: '#7a6556', accent: '#c4533e', detail: '#5b4a5a', outline: '#120e0c' } });
