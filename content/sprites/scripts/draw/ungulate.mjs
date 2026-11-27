// Draws categories/ungulate.svg: `npm run draw -- ungulate`.
// A stag-like beast facing right: a big barrel body held high on four long straight legs with dark
// hooves (detail), a neck rising to a long head with a muzzle, two tall horns (accent) and a short tail.
// Anchors: `head` (between the horns), `back` (a saddle or pack), `impact`.
import { sheet, S, f, leaf, limbs, tube } from './kit.mjs';

/**
 * body: [x, y]; head: [x, y] (the skull centre); legs: [x, foot y] four times, back to front (the top rides the
 * body); horn: degrees the horns point (-90 up, 0 forward); tail: [dx, dy] of the tip from the rump.
 */
function beast({ body: [bx, by], head: [hx, hy], legs, horn = -60, hl = 6, tail = [-2, 3] }) {
  const legD = legs.map(([x, y]) => [[x, by + 1], [x, y]]);
  const hooves = legs.map(([x, y]) => [[x, y - 1.8], [x, y]]);
  const neck = tube([[bx + 5, by - 1], [(bx + 5 + hx) / 2, (by - 1 + hy) / 2 + 1], [hx, hy + 1]], [5.5, 4.6, 4]);
  const horns = [horn - 22, horn + 12].map((d, i) => `<path d="${leaf(hx - 1 + i * 1.5, hy - 2, hl, 2.4, d)}" fill="${S.accent}"/>`).join('');
  return {
    svg: [
      `<path d="M${f(bx - 8)} ${f(by - 1)}L${f(bx - 8 + tail[0])} ${f(by + tail[1])}" fill="none" stroke="${S.outline}" stroke-width="5"/>`,
      `<path d="M${f(bx - 8)} ${f(by - 1)}L${f(bx - 8 + tail[0])} ${f(by + tail[1])}" fill="none" stroke="${S.detail}" stroke-width="2"/>`,
      limbs(legD, S.body, 2.6),
      limbs(hooves, S.detail, 2.8),
      `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="8.5" ry="4.8" fill="${S.body}"/>`,
      `<path d="${neck}" fill="${S.body}"/>`,
      horns,
      `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="3.4" ry="3" fill="${S.body}"/>`,
      `<ellipse cx="${f(hx + 4.2)}" cy="${f(hy + 1.8)}" rx="4" ry="2.3" fill="${S.detail}"/>`,
      `<circle cx="${f(hx + 7.5)}" cy="${f(hy + 1.4)}" r="1.1" fill="${S.outline}" stroke="none"/>`,
      `<circle cx="${f(hx + 0.8)}" cy="${f(hy - 0.4)}" r="1" fill="${S.accent}" stroke="none"/>`,
    ].join(''),
    anchors: { head: [hx - 0.5, hy - 5], back: [bx - 1, by - 6], impact: [hx + 1, hy - 4] },
  };
}

const legsAt = (y, bx, xs = [-6, -3, 5, 8]) => xs.map((x) => [x + bx + 2, y]);
const base = { body: [-2, -1], head: [7, -6.6] };
const stride = { ...base, legs: [[-10, 10], [-6, 7], [5, 7], [10, 10]] };
const gather = { ...base, body: [-2, -2], legs: [[-6, 8.5], [-2, 8.5], [4, 8.5], [8, 8.5]] };
sheet('ungulate', {
  idle: [
    beast({ ...base, legs: legsAt(11.5, -2) }),
    beast({ body: [-2, -0.2], head: [6.8, -5.4], legs: legsAt(11.5, -2), tail: [-2, 4] }),
  ],
  moving: [
    beast(stride),
    beast(gather),
    beast({ ...stride, legs: [[-6, 7], [-10, 10], [10, 10], [5, 7]] }),
    beast({ ...gather, legs: [[-9, 8.5], [-5, 7], [1, 7], [6, 8.5]] }),
  ],
  // Head lowered, horns levelled: the charge.
  acting: [
    beast({ body: [-3, 0], head: [5.5, -2], horn: -20, hl: 8, legs: legsAt(11.5, -3, [-7, -4, 3, 6]) }),
    beast({ body: [-3, 1], head: [6.5, 1], horn: 5, hl: 8, legs: legsAt(11.5, -3, [-8, -5, 4, 8]) }),
  ],
  // Struck: it rears back, then shrinks down with head low.
  'acted-upon': [
    beast({ body: [-3, 0], head: [3, -6.6], horn: -80, legs: legsAt(11.5, -3, [-5, -2, 5, 9]), tail: [-2, 1] }),
    beast({ body: [-3, 2], head: [5, 1], horn: -30, legs: legsAt(11.5, -3, [-6, -3, 5, 8]), tail: [-2, 5] }),
  ],
}, { sample: { body: '#6d5038', accent: '#d8ccb0', detail: '#3f2c1d', outline: '#1a120b' } });
