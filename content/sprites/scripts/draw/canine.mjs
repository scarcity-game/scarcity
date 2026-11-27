// Draws categories/canine.svg: `npm run draw -- canine`.
// A dog facing right: a long body on four straight legs (detail), a pointed
// ear, a long muzzle (detail) with a dark nose, an accent eye and a raised tail.
// Anchors: `head` (headgear, between the ears), `back` (a pack or saddle), `impact`.
import { sheet, S, f } from './kit.mjs';

/**
 * body: [x, y]; head: [x, y]; legs: [x, foot y] four times, back to front (the top rides the body);
 * tail: [start x, start y, tip x, tip y]; bite: jaws open under the muzzle.
 */
function dog({ body: [bx, by], head: [hx, hy], legs, tail: [tx, ty, ex, ey], bite = false }) {
  const tailD = `M${f(tx)} ${f(ty)}Q${f(tx - 3)} ${f(ty - 2)} ${f(ex)} ${f(ey)}`;
  const legD = legs.map(([x, y]) => `M${f(x)} ${f(by + 2)}V${f(y)}`).join('');
  const jaws = bite
    ? `<polygon points="${f(hx + 4)},${f(hy + 2.2)} ${f(hx + 10)},${f(hy + 2.2)} ${f(hx + 8)},${f(hy + 6.2)}" fill="${S.outline}"/>`
      + `<polygon points="${f(hx + 6)},${f(hy + 2.4)} ${f(hx + 8)},${f(hy + 2.4)} ${f(hx + 7.4)},${f(hy + 4.2)}" fill="${S.accent}" stroke="none"/>`
    : '';
  const svg = [
    `<path d="${tailD}" fill="none" stroke="${S.outline}" stroke-width="5"/>`,
    `<path d="${tailD}" fill="none" stroke="${S.detail}" stroke-width="2.5"/>`,
    `<path d="${legD}" fill="none" stroke="${S.outline}" stroke-width="5"/>`,
    `<path d="${legD}" fill="none" stroke="${S.detail}" stroke-width="2"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="8.5" ry="5.5" fill="${S.body}"/>`,
    `<polygon points="${f(hx - 2)},${f(hy - 3)} ${f(hx - 1)},${f(hy - 9)} ${f(hx + 3)},${f(hy - 3.5)}" fill="${S.body}"/>`,
    `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="4.5" ry="4" fill="${S.body}"/>`,
    `<ellipse cx="${f(hx + 5)}" cy="${f(hy + 1.2)}" rx="4.5" ry="2.4" fill="${S.detail}"/>`,
    jaws,
    `<circle cx="${f(hx + 9)}" cy="${f(hy + 0.4)}" r="1.4" fill="${S.outline}" stroke="none"/>`,
    `<circle cx="${f(hx + 2)}" cy="${f(hy - 1)}" r="1.1" fill="${S.accent}" stroke="none"/>`,
  ].join('');
  return { svg, anchors: { head: [hx, hy - 4.5], back: [bx, by - 5.5], impact: [hx + 1, hy - 8] } };
}

const legsAt = (y, xs = [-7, -3, 4, 8]) => xs.map((x) => [x, y]);
const stride = { body: [-2, 2], head: [4.5, -1], legs: [[-9, 10], [-6, 7], [6, 7], [11, 10]], tail: [-9, 1, -12.5, -3] };
const gather = { body: [-2, 1], head: [4.5, -2], tail: [-9, -1, -12.5, -6] };
sheet('canine', {
  idle: [
    dog({ body: [-2, 2], head: [4.5, -1], legs: legsAt(10), tail: [-8, 1, -12, -6] }),
    dog({ body: [-2, 1.3], head: [4.5, -2.4], legs: legsAt(9.3), tail: [-8, 0.3, -12, -6.7] }),
  ],
  // A trot: legs reaching out, gathered in, out again, then the other pair gathered.
  moving: [
    dog(stride),
    dog({ ...gather, legs: [[-5, 9], [-1, 9], [5, 9], [9, 9]] }),
    dog(stride),
    dog({ ...gather, legs: [[-9, 9], [-5, 7], [1, 7], [6, 9]] }),
  ],
  // Head down to lunge, then the bite.
  acting: [
    dog({ body: [-2, 3], head: [5.5, 2], legs: legsAt(10, [-8, -4, 3, 7]), tail: [-8, 2, -12, -5] }),
    dog({ body: [-1, 2], head: [5, 2], legs: legsAt(10, [-8, -4, 4, 8]), tail: [-8, 1, -12, -6], bite: true }),
  ],
  // Struck: it drops, then cowers with head and tail low.
  'acted-upon': [
    dog({ body: [-2, 3], head: [5, 3], legs: legsAt(10, [-8, -4, 3, 8]), tail: [-8, 2, -12, -5] }),
    dog({ body: [-2, 3], head: [4, 4], legs: legsAt(9, [-8, -4, 3, 8]), tail: [-9, 3, -13, -1] }),
  ],
});
