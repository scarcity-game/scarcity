// Draws categories/dragon.svg: `npm run draw -- dragon`.
// A heavy four-legged reptile facing right: a barrel body on stout legs, a tapering tail behind, two big
// wings raised high over the back, a long neck rising to a horned head. Wings and neck are the read.
import { sheet, S, f, tube, leaf, limbs, turn } from './kit.mjs';

const P = (pts) => pts.map(([x, y]) => `${f(x)} ${f(y)}`);

/** A raised wing: arm `len` long from the shoulder `s` at `deg`, a scalloped web trailing on the left of it. */
function wing(s, deg, len, fill) {
  const local = [[0, 0], [len, 0], [len * 0.95, -4.5], [len * 0.78, -2.8], [len * 0.62, -8.5], [len * 0.42, -5], [len * 0.2, -8], [0, -3]];
  const ring = turn(local, deg).map(([x, y]) => [s[0] + x, s[1] + y]);
  const fingers = [local[2], local[4], local[6]].map((t) => turn([[len, 0], t], deg).map(([x, y]) => [s[0] + x, s[1] + y]));
  const arm = turn([[0, 0], [len, 0]], deg).map(([x, y]) => [s[0] + x, s[1] + y]);
  const d = [arm, fingers[1]].map((l) => `M${P(l).join('L')}`).join('');
  return `<path d="M${P(ring).join('L')}Z" fill="${fill}"/><path d="${d}" fill="none" stroke-width="1.5"/>`;
}

function dragon({ dx = 0, dy = 0, flap = 0, neck = 0, jaw = 0, crouch = 0, tail = 0, lean = 0 }) {
  const bx = dx - 2.5;
  const by = dy + 6 + crouch;
  const s = [bx + 1, by - 3];
  // The neck rises from the chest: `neck` raises the head, `lean` throws it back or forward.
  const mid = [bx + 7.5 + lean * 0.5, by - 7 - neck * 0.5];
  const hx = bx + 10 + lean;
  const hy = by - 13.5 - neck;
  const legY = by + 4.5;
  const svg = [
    wing([s[0] - 1, s[1]], -128 - flap * 5, 12.5, S.detail),
    wing(s, -82 - flap * 6, 14, S.detail),
    `<path d="${tube([[bx - 6, by + 0.5], [bx - 9, by + 3.5 - tail], [bx - 10, by - 0.5 - tail * 2]], [5, 3.4, 1])}" fill="${S.body}"/>`,
    limbs([[[bx - 4, by + 2.5], [bx - 5, legY + 1.5]], [[bx + 5, by + 2.5], [bx + 6, legY + 1.5]]], S.detail, 3.4),
    `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="8.5" ry="5" fill="${S.body}"/>`,
    `<path d="${tube([[bx + 4, by - 1], [bx + 7, by - 3.5], mid, [hx - 1.5, hy + 2.5]], [6, 5, 4.4, 3.8])}" fill="${S.body}"/>`,
    limbs([[[bx - 1, by + 3], [bx - 2, legY]], [[bx + 3, by + 3], [bx + 4, legY]]], S.body, 3.4),
    `<ellipse cx="${f(bx)}" cy="${f(by + 2.2)}" rx="4.5" ry="2" fill="${S.detail}" stroke="none"/>`,
    `<path d="${leaf(hx - 2, hy - 1.5, 6.5, 1.6, -150)}" fill="${S.accent}"/>`,
    `<path d="M${P([[hx - 3, hy - 2.5], [hx + 1, hy - 3], [hx + 5.5, hy - 1], [hx + 5.5, hy + 1], [hx + 1, hy + 2], [hx - 3, hy + 2]]).join('L')}Z" fill="${S.body}"/>`,
    `<path d="M${P([[hx, hy + 1.5], [hx + 5, hy + 1.2 + jaw * 0.4], [hx + 4.6 + jaw * 0.2, hy + 3.2 + jaw], [hx + 0.5, hy + 3]]).join('L')}Z" fill="${S.detail}"/>`,
    `<circle cx="${f(hx + 1.2)}" cy="${f(hy - 0.8)}" r="1" fill="${S.accent}" stroke="none"/>`,
  ].join('');
  return {
    svg,
    anchors: { impact: [hx + 1.5, hy - 4], head: [hx - 1, hy - 3], back: [bx, by - 5], mouth: [hx + 5.5, hy + 1.5 + jaw * 0.5] },
  };
}

sheet('dragon', {
  idle: [dragon({}), dragon({ flap: 0.8, neck: -1, dy: 0.5, tail: 1 })],
  moving: [
    dragon({ flap: 1, dy: -0.5, tail: 1 }),
    dragon({ flap: 0, dy: 0.5, dx: 0.5 }),
    dragon({ flap: -1, dy: -0.5, tail: -1, dx: 0.5 }),
    dragon({ flap: 0, dy: 0.5 }),
  ],
  acting: [dragon({ neck: 2, lean: -2, jaw: 0.5, flap: 1 }), dragon({ neck: 1.5, lean: 1.5, jaw: 3, flap: -0.5, dx: 0.5 })],
  'acted-upon': [dragon({ dx: -0.5, lean: -3, neck: 1.5, flap: 1.5, jaw: 1.5 }), dragon({ crouch: 1, neck: -4, lean: -1, flap: -1.5, dx: -0.5 })],
}, { sample: { body: '#9a4636', accent: '#e8a23a', detail: '#5f2a20', outline: '#1d0d0a' } });
