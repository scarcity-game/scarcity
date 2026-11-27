// Draws categories/serpent.svg: `npm run draw -- serpent`.
// A snake facing right: a thick body in a wave on the ground, rising in an S
// to a raised wedge head with the tongue out. The wave's phase slides for
// moving; acting rears back, then strikes with the jaw open.
import { sheet, S, f, star } from './kit.mjs';

const path = (pts) => pts.map(([x, y], i) => `${i ? 'L' : 'M'}${f(x)} ${f(y)}`).join('');

/**
 * The spine from the tail to the head: a wave along the ground, then a neck
 * that rises in a bend. `rear` raises the head, `lean` pushes it forwards,
 * `flat` lowers the whole neck (cower), `phase` slides the wave.
 */
function spine({ phase = 0, rear = 0, lean = 0, flat = 0, dx = 0 }) {
  const pts = [];
  for (let i = 0; i <= 6; i++) {
    const s = i / 6;
    pts.push([-11.5 + s * 13.5, 8.3 + 3.6 * Math.sin(phase + s * 6.3) * (0.5 + s * 0.5)]);
  }
  const hx = 3 + lean;
  const hy = -4 - rear + flat * 8;
  const [x0, y0] = pts[6];
  const c1 = [x0 + 6, y0 - 1];
  const c2 = [hx - 8 - lean * 0.5, hy + 8];
  for (let i = 1; i <= 5; i++) {
    const t = i / 5;
    const u = 1 - t;
    pts.push([
      u ** 3 * x0 + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * hx,
      u ** 3 * y0 + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * hy,
    ]);
  }
  return pts.map(([x, y]) => [x + dx, y]);
}

function snake({ jaw = 0, hit = 0, ...o }) {
  const pts = spine(o);
  const [hx, hy] = pts[pts.length - 1];
  const tail = pts.slice(0, 4);
  const mid = pts.slice(3);
  const marks = [pts[4], pts[8]].map(([x, y]) => `<circle cx="${f(x)}" cy="${f(y)}" r="1.1"/>`).join('');
  return [
    // The tail tip is thinner; the rest of the body is one thick stroke, outline pass first.
    `<path d="${path(tail)}" fill="none" stroke-width="5"/>`,
    `<path d="${path(tail)}" fill="none" stroke="${S.body}" stroke-width="2"/>`,
    `<path d="${path(mid)}" fill="none" stroke-width="7.5"/>`,
    `<path d="${path(mid)}" fill="none" stroke="${S.body}" stroke-width="4.5"/>`,
    `<g fill="${S.accent}" stroke="none">${marks}</g>`,
    // Tongue, then the wedge head with the lower jaw dropped by `jaw`.
    `<path d="M${f(hx + 5)} ${f(hy + 1)}L${f(hx + 8 + jaw)} ${f(hy + 1)}M${f(hx + 7)} ${f(hy + 1)}L${f(hx + 9 + jaw)} ${f(hy + 2.5)}" fill="none" stroke="${S.accent}" stroke-width="1.5"/>`,
    jaw ? `<path d="M${f(hx)} ${f(hy + 1)}L${f(hx + 5)} ${f(hy + 2 + jaw * 2.5)}L${f(hx + 5)} ${f(hy + 1)}Z" fill="${S.detail}"/>` : '',
    `<ellipse cx="${f(hx + 1)}" cy="${f(hy)}" rx="5.5" ry="3.3" fill="${S.body}"/>`,
    `<circle cx="${f(hx + 2.5)}" cy="${f(hy - 0.7)}" r="1.2" fill="${S.accent}" stroke="none"/>`,
    hit ? star(hx - 9, hy + 1, hit) : '',
  ].join('');
}

sheet('serpent', {
  idle: [snake({}), snake({ rear: 2, lean: 1, phase: 0.5 })],
  moving: [0, 1.6, 3.2, 4.8].map((phase) => snake({ phase, dx: (phase - 2.4) / 6 })),
  acting: [snake({ rear: 3, lean: -3 }), snake({ lean: 2, rear: -2, jaw: 3, phase: 1, dx: -1.8 })],
  'acted-upon': [snake({ rear: 4, lean: -3, hit: 4.5 }), snake({ flat: 1, lean: -3, phase: 2, hit: 3 })],
});
