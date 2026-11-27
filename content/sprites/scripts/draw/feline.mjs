// Draws categories/feline.svg: `npm run draw -- feline`.
// A cat facing right: a long, low, arched body on four short legs (detail), a small round head with
// two triangular ears and no muzzle, accent eyes, and a long tail hooked up over the back.
// Anchors: `head` (headgear, between the ears), `back` (a pack or saddle), `impact`.
import { sheet, S, f } from './kit.mjs';

const SHIFT = 2;

/**
 * body: [x, y]; head: [x, y]; legs: [x, foot y] four times, back to front (the top rides the body);
 * tail: three points after the rump [x, y, x, y, x, y]; arch: the back's hump; ears: 0 up, 1 flat; bite: open jaws.
 */
function cat({ body: [bx0, by], head: [hx0, hy], legs: legs0, tail, arch = 0, ears = 0, bite = false, len = 0 }) {
  // Everything is drawn about the origin, then moved right: the curled tail takes the left of the box.
  const [bx, hx] = [bx0 + SHIFT, hx0 + SHIFT];
  const legs = legs0.map(([x, y]) => [x + SHIFT, y]);
  const [t1x, t1y, t2x, t2y, t3x, t3y] = tail.map((n, i) => (i % 2 ? n : n + SHIFT + 0.8));
  const tailD = `M${f(bx - 8)} ${f(by)}Q${f(t1x)} ${f(t1y)} ${f(t2x)} ${f(t2y)}Q${f(t3x)} ${f(t3y)} ${f(t3x + 3)} ${f(t3y - 0.5)}`;
  const legD = legs.map(([x, y]) => `M${f(x)} ${f(by + 2)}V${f(y)}`).join('');
  const e = ears * 2;
  const ear = (dx) => `<polygon points="${f(hx + dx - 2.2)},${f(hy - 2)} ${f(hx + dx + 0.3 - e * 0.5)},${f(hy - 8 + e * 1.5)} ${f(hx + dx + 2.4)},${f(hy - 2.4)}" fill="${S.body}"/>`;
  const jaws = bite
    ? `<polygon points="${f(hx + 2)},${f(hy + 1.6)} ${f(hx + 7)},${f(hy + 2.6)} ${f(hx + 3.6)},${f(hy + 5.4)}" fill="${S.outline}"/>`
      + `<polygon points="${f(hx + 3.4)},${f(hy + 1.8)} ${f(hx + 5.2)},${f(hy + 2.2)} ${f(hx + 4.4)},${f(hy + 3.8)}" fill="${S.accent}" stroke="none"/>`
    : '';
  const svg = [
    `<path d="${tailD}" fill="none" stroke="${S.outline}" stroke-width="5"/>`,
    `<path d="${tailD}" fill="none" stroke="${S.body}" stroke-width="2.6"/>`,
    `<path d="${legD}" fill="none" stroke="${S.outline}" stroke-width="5"/>`,
    `<path d="${legD}" fill="none" stroke="${S.detail}" stroke-width="2.4"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="${f(9.5 + len)}" ry="${f(4.2 + arch * 0.4)}" fill="${S.body}"/>`,
    `<ellipse cx="${f(bx - 0.5)}" cy="${f(by - 3.4 - arch)}" rx="5.5" ry="2.4" fill="${S.body}" stroke="none"/>`,
    `<path d="M${f(bx - 3)} ${f(by - 4.2 - arch * 0.5)}L${f(bx - 2)} ${f(by - 1)}M${f(bx + 1)} ${f(by - 4.6 - arch * 0.5)}L${f(bx + 2)} ${f(by - 1.4)}" fill="none" stroke="${S.detail}" stroke-width="1.8"/>`,
    ear(-2.4),
    ear(2.2),
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="4.8" fill="${S.body}"/>`,
    jaws,
    `<g fill="${S.accent}" stroke="none"><ellipse cx="${f(hx + 1.2)}" cy="${f(hy - 0.8)}" rx="1.2" ry="1.4"/><ellipse cx="${f(hx + 4.2)}" cy="${f(hy - 0.8)}" rx="1" ry="1.3"/></g>`,
  ].join('');
  return { svg, anchors: { head: [hx, hy - 5.5], back: [bx, by - 5.5 - arch], impact: [hx + 1, hy - 9] } };
}

const legsAt = (y, xs = [-7.5, -4, 4, 7.5]) => xs.map((x) => [x, y]);
const tailUp = [-15, 4, -13, -3, -12, -9];
// The slink: body stretched and low, head forward, tail held low and straight back.
const slink = { len: 2, head: [7.5, 3.8], tail: [-14, 7, -15, 5.5, -15.5, 4] };
sheet('feline', {
  idle: [
    cat({ body: [-2, 4], head: [6, -0.5], legs: legsAt(11.5), tail: tailUp }),
    cat({ body: [-2, 4.6], head: [6, 0.2], legs: legsAt(11.5), tail: [-15, 4, -14, -4, -9.5, -10] }),
  ],
  // A slink: the body bobs low (0, 2) and up (1, 3) while the pairs of legs spread 11 units apart and close under the belly.
  moving: [
    cat({ ...slink, body: [-3, 6.8], legs: [[-12, 11.5], [-9, 11.5], [6, 11.5], [10, 11.5]] }),
    cat({ ...slink, body: [-3, 5.4], head: [7.5, 2.8], legs: [[-6, 11], [-3, 11], [0, 11], [3, 11]] }),
    cat({ ...slink, body: [-3, 6.8], legs: [[-11, 11.5], [-8, 11.5], [5, 11.5], [9, 11.5]] }),
    cat({ ...slink, body: [-3, 5.4], head: [7.5, 2.8], legs: [[-5, 11], [-2, 11], [1, 11], [4, 11]], tail: [-14, 5, -15, 4, -15.5, 2.5] }),
  ],
  // Crouched, rump up and tail low; then the pounce, stretched out with open jaws.
  acting: [
    cat({ body: [-3, 6], head: [5, 4], legs: legsAt(11.5, [-9, -6, 2, 6]), tail: [-14, 7, -14, 3, -13, 0], arch: 1 }),
    cat({ body: [-3.5, 3], head: [5, 2], legs: legsAt(10, [-13, -9, 1.5, 5.5]), tail: [-13, 3, -13, -2, -11, -4], bite: true }),
  ],
  // Struck: it jolts back arched with the tail bushed up, then cowers flat, ears back.
  'acted-upon': [
    cat({ body: [-4, 3.5], head: [3.5, -1], legs: legsAt(11.5, [-9, -6, 1, 4]), tail: [-14, 0, -14, -7, -11, -11], arch: 2.5, ears: 0.5 }),
    cat({ body: [-2, 7], head: [4.5, 6.5], legs: legsAt(11.5, [-8, -4, 3, 7]), tail: [-14, 8, -14, 6, -10, 8], ears: 1 }),
  ],
}, { sample: { body: '#9c7a4a', accent: '#d9c35a', detail: '#5e4628', outline: '#1f170d' } });
