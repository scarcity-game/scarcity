// Draws categories/rodent.svg: `npm run draw -- rodent`.
// A mouse facing right: a round body, the head forward with a big round ear
// (accent inside), a pink snout, two feet and a curled tail (accent).
// Anchors: `head` (headgear, on the crown), `back` (a pack), `impact`.
import { sheet, S, f } from './kit.mjs';

const ell = (cx, cy, rx, ry, fill, extra = '') =>
  `<ellipse cx="${f(cx)}" cy="${f(cy)}" rx="${f(rx)}" ry="${f(ry)}" fill="${fill}"${extra}/>`;
const dot = (cx, cy, r, fill, extra = '') => `<circle cx="${f(cx)}" cy="${f(cy)}" r="${f(r)}" fill="${fill}"${extra}/>`;

/**
 * body: [cx, cy, rx, ry]; head: [x, y]; feet: [back x, front x, y]; tail: [start x, start y, tip y];
 * ear: how much higher the ear sits; bite: open mouth with a tooth instead of the snout.
 */
function mouse({ body: [bx, by, rx, ry], head: [hx, hy], feet: [f0, f1, fy], tail: [tx, ty, tip], ear = 0, bite = false }) {
  const tailD = `M${f(tx)} ${f(ty)}Q-13.5 ${f(ty)} -13 ${f(tip)}`;
  const [ex, ey] = [hx - 3, hy - 6 - ear];
  const mouth = bite
    ? `<polygon points="${f(hx + 2.5)},${f(hy + 1)} ${f(hx + 6)},${f(hy - 2.5)} ${f(hx + 6)},${f(hy + 5.5)}" fill="${S.outline}"/>`
      + `<polygon points="${f(hx + 4)},${f(hy + 3)} ${f(hx + 5.5)},${f(hy + 2.2)} ${f(hx + 5.5)},${f(hy + 5)}" fill="${S.accent}" stroke="none"/>`
    : dot(hx + 5, hy + 0.5, 1.6, S.accent);
  const svg = [
    `<path d="${tailD}" fill="none" stroke="${S.outline}" stroke-width="4"/>`,
    `<path d="${tailD}" fill="none" stroke="${S.accent}" stroke-width="2"/>`,
    ell(f0, fy, 3, 1.8, S.detail), ell(f1, fy, 3, 1.8, S.detail),
    ell(bx, by, rx, ry, S.body),
    // The front haunch, in the detail colour, rides the body.
    ell(bx + 8, by + 1.7, 5, 3, S.detail, ' stroke="none"'),
    ell(hx, hy, 5.5, 4.5, S.body),
    mouth,
    dot(ex, ey, 4, S.body), dot(ex, ey, 2, S.accent, ' stroke="none"'),
    dot(hx + 2, hy - 1.5, 1.1, S.outline, ' stroke="none"'),
  ].join('');
  return { svg, anchors: { head: [ex + 1, ey - 1], back: [bx - 1, by - ry], impact: [hx + 1, hy - 8] } };
}

const trot = { body: [-1, 2, 10.5, 5.5], head: [8.5, 1] };
const hop = { body: [-2, 0.5, 9.5, 7.2], head: [7.5, -0.5] };
sheet('rodent', {
  idle: [
    mouse({ body: [-2, 1.5, 9.5, 6.5], head: [7.5, 0.5], feet: [-5, 4, 7.5], tail: [-9, 2, -5] }),
    mouse({ body: [-2, 1.5, 9.5, 6.5], head: [8, -0.5], feet: [-5, 4, 7.5], tail: [-9, 2, -5], ear: 0.8 }),
  ],
  // Stretched out, then bunched up and off the ground.
  moving: [
    mouse({ ...trot, feet: [-9, 8, 8], tail: [-8, 2.5, -2.5] }),
    mouse({ ...hop, feet: [-4, 3, 5], tail: [-9, 1, -9] }),
    mouse({ ...trot, feet: [-8, 9, 8], tail: [-8, 2.5, -1.5] }),
    mouse({ ...hop, feet: [-3, 4, 5], tail: [-9, 1, -10] }),
  ],
  // Crouch, then lunge with teeth bared.
  acting: [
    mouse({ body: [-2, 2.5, 9.5, 5.5], head: [6, 2.5], feet: [-6, 3, 8.5], tail: [-9, 3, -3] }),
    mouse({ body: [-0.5, 1.5, 9.5, 6.5], head: [9, 0], feet: [-8, 5, 7.5], tail: [-7.5, 2, -3], bite: true }),
  ],
  // Struck: flattened with the ear back, then it jumps up with the tail flung high.
  'acted-upon': [
    mouse({ body: [-2, 3, 11, 5], head: [7.5, 3], feet: [-8, 7, 9], tail: [-9, 3.5, -0.5], ear: -1 }),
    mouse({ body: [-2, -1.5, 9.5, 6], head: [7.5, -3.5], feet: [-8, 8, 6], tail: [-9, -1, -12] }),
  ],
});
