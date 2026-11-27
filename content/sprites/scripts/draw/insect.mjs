// Draws categories/insect.svg: `npm run draw -- insect`.
// A wasp facing right, hovering: head in front, thorax, a striped abdomen with
// a stinger behind, two big translucent wings held up and back, thin legs below.
import { sheet, S, f, star, limbs } from './kit.mjs';

/** The hit star in the light detail slot: the accent is near black and vanishes on dark ground. */
const hitStar = (x, y, r) => star(x, y, r).replace(S.accent, S.detail);

/** A leaf-shaped wing from its root to a tip, `w` wide at the middle. */
function wing([rx, ry], [tx, ty], w) {
  const len = Math.hypot(tx - rx, ty - ry);
  const mx = (rx + tx) / 2;
  const my = (ry + ty) / 2;
  const nx = (ty - ry) / len;
  const ny = -(tx - rx) / len;
  const c1 = [mx + nx * w, my + ny * w];
  const c2 = [mx - nx * w, my - ny * w];
  return `M${f(rx)} ${f(ry)}Q${f(c1[0])} ${f(c1[1])} ${f(tx)} ${f(ty)}Q${f(c2[0])} ${f(c2[1])} ${f(rx)} ${f(ry)}Z`;
}

/**
 * flap: 0 wings fully up .. 1 swept down and back. curl: abdomen bent down and
 * forward, sting: the stinger pushed out. cower: tucked, legs and wings in.
 */
function wasp({ dx = 0, dy = 0, flap = 0, curl = 0, sting = 0, cower = 0, hit = 0 }) {
  const p = (px, py) => [px + dx, py + dy];
  const [tx, ty] = p(0, 3);
  const [hx, hy] = p(7, 3.5 + curl * 0.3);
  // The abdomen hangs behind the thorax; curl swings it down and under.
  const [ax, ay] = p(-6.5 + curl * 3.5, 5 + curl * 3);
  const [sx, sy] = p(-12 + curl * 9 + sting * 3, 7 + curl * 5.5);
  const far = wing(p(-1, 0), p(-10 - flap * 2 + cower * 5, -13 + flap * 9 + cower * 6), 8);
  const near = wing(p(1, 0), p(2 - flap * 3 - cower * 3, -14 + flap * 8 + cower * 6), 7);
  const lift = cower * 1.5;
  const legs = [
    [[-1, 6], [-2, 10 - lift], [-3, 13 - lift]],
    [[3, 6], [4, 10 - lift], [6, 12.5 - lift]],
  ].map((l) => l.map(([x, y]) => p(x, y)));
  return [
    `<g fill="${S.detail}" fill-opacity="0.8"><path d="${far}"/><path d="${near}"/></g>`,
    limbs(legs, S.accent, 1.5),
    `<ellipse cx="${f(ax)}" cy="${f(ay)}" rx="6" ry="4.2" fill="${S.body}"/>`,
    `<path d="M${f(sx + 3.5)} ${f(sy - 1.6)}L${f(sx - 0.5)} ${f(sy + 0.4)}L${f(sx + 3.5)} ${f(sy + 1.6)}Z" fill="${S.accent}"/>`,
    // Dark stripes across the abdomen, no stroke.
    `<g fill="${S.accent}" stroke="none"><rect x="${f(ax - 3.2)}" y="${f(ay - 3.6)}" width="2" height="7.2"/>`
      + `<rect x="${f(ax + 0.8)}" y="${f(ay - 3.8)}" width="2" height="7.4"/></g>`,
    `<circle cx="${f(tx)}" cy="${f(ty)}" r="4.2" fill="${S.body}"/>`,
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="3.5" fill="${S.body}"/>`,
    `<circle cx="${f(hx + 1.2)}" cy="${f(hy - 0.8)}" r="1.2" fill="${S.accent}" stroke="none"/>`,
    hit ? hitStar(hx + 1, hy - 9, hit) : '',
  ].join('');
}

sheet('insect', {
  idle: [wasp({ flap: 0 }), wasp({ flap: 0.7, dy: 0.8 })],
  moving: [
    wasp({ flap: 0, dx: -0.5 }),
    wasp({ flap: 0.8, dx: 0.3, dy: 1 }),
    wasp({ flap: 0, dx: 1 }),
    wasp({ flap: 0.8, dx: 0.3, dy: 1 }),
  ],
  acting: [wasp({ curl: 0.6, flap: 0.5 }), wasp({ curl: 1, sting: 1, flap: 0, dx: 1 })],
  'acted-upon': [wasp({ dx: -1, flap: 0.9, hit: 5 }), wasp({ dy: 1.5, cower: 1, hit: 3 })],
});
