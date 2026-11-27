// Draws categories/demon.svg: `npm run draw -- demon`.
// An upright, two-legged humanoid facing right with two horns, big pointed bat wings rising behind the
// shoulders and a barbed tail. Skin is body, wings and horns are detail (kept light enough to read on dark
// ground), eyes and claws accent. Anchors: `head` (between the horns, for a crown), `hand.main` and
// `hand.off` (with the forearm's angle: the hand holds fire) and `impact`.
import { sheet, S, f, bar, leaf, tube, turn } from './kit.mjs';

const P = (pts) => pts.map(([x, y]) => `${f(x)} ${f(y)}`).join('L');

/** A leg from the hip to a foot on the ground pointing right. */
const leg = ([hip, foot], top) => `<polygon points="${f(hip - 2.2)},${f(top)} ${f(hip + 2.2)},${f(top)} `
  + `${f(foot + 2.2)},12.5 ${f(foot + 4.2)},13.8 ${f(foot - 2.2)},13.8" fill="${S.body}"/>`;

const hand = ([x, y]) => `<circle cx="${f(x)}" cy="${f(y)}" r="2" fill="${S.accent}"/>`;

/** A bat wing from the shoulder `s`: an arm up to the tip, a scalloped membrane hanging down behind the back. */
function wing(s, flap) {
  const local = [[0, 0], [-9, -12], [-12, -4.5], [-9.5, -5.5], [-13, 2.5], [-9, 1], [-7, 7], [-3, 3]].map(([x, y]) => [x * 0.88, y * 0.88]);
  const ring = turn(local, flap).map(([x, y]) => [s[0] + x, s[1] + y]);
  const arm = turn([[0, 0], [-9, -12]], flap).map(([x, y]) => [s[0] + x, s[1] + y]);
  const rib = turn([[-9, -12], [-9.5, -5.5], [-9, 1]], flap).map(([x, y]) => [s[0] + x, s[1] + y]);
  return `<path d="M${P(ring)}Z" fill="${S.detail}"/><path d="M${P(arm)}M${P(rib)}" fill="none" stroke-width="1.5"/>`;
}

/**
 * t: torso shift right (a lean); b: bob down; squat: shorter from the top. legs: [hip, foot] x of both legs;
 * off, main: the hands; flap: wing angle (negative raises them); tail: how high the tail tip curls.
 */
function demon({ t = 0, b = 0, squat = 0, legs = [[-3, -3], [3, 3]], off = [-7, 4], main = [9, 4], flap = 0, tail = 0 }) {
  const top = -3 + b + squat;
  const [hx, hy] = [1.5 + t, -6.5 + b + squat];
  const shoulder = -0.5 + b + squat;
  const offArm = bar([-3.5 + t, shoulder], off, S.body);
  const mainArm = bar([4.5 + t, shoulder], main, S.body);
  // A horn is wide at the head and tapers to a sharp tip that curves outward (`out`: -1 left, 1 right).
  const horn = (dx, out) => {
    const [bx, by] = [hx + dx, hy - 2.5];
    return `<path d="${tube([[bx, by + 0.5], [bx + out * 0.7, by - 3], [bx + out * 2.4, by - 6]], [3, 2, 0])}" fill="${S.detail}"/>`;
  };
  const svg = [
    wing([-1 + t, shoulder - 1], flap * 0.5),
    `<path d="${tube([[-3 + t, 5 + b], [-8, 8 - tail], [-10.5, 6 - tail * 2]], [3.5, 3, 1.5])}" fill="${S.body}"/>`,
    `<path d="${leaf(-10.5, 6 - tail * 2, 3.5, 2.2, -150 - tail * 6)}" fill="${S.detail}"/>`,
    offArm.svg, hand(off),
    leg(legs[0], 5 + b), leg(legs[1], 5 + b),
    `<polygon points="${f(-4.5 + t)},${f(top)} ${f(5.5 + t)},${f(top)} ${f(6.5 + t)},${f(6 + b)} ${f(-5.5 + t)},${f(6 + b)}" fill="${S.body}"/>`,
    horn(-2.5, -1), horn(2.5, 1),
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="4.4" fill="${S.body}"/>`,
    `<circle cx="${f(hx + 2.6)}" cy="${f(hy - 0.6)}" r="1.1" fill="${S.accent}" stroke="none"/>`,
    mainArm.svg, hand(main),
  ].join('');
  return {
    svg,
    anchors: {
      head: [hx, hy - 4.5],
      'hand.main': [...main, mainArm.deg],
      'hand.off': [...off, offArm.deg],
      impact: [hx + 3, hy - 6],
    },
  };
}

const walk = { b: -1, off: [-7, 3], main: [8, 3], legs: [[-2, -2], [2.5, 2.5]], flap: 5, tail: 1 };
sheet('demon', {
  idle: [demon({}), demon({ b: 0.8, off: [-7, 5.6], main: [9, 5.6], flap: 9, tail: 1.5 })],
  moving: [
    demon({ off: [-8, 3], main: [4, 5.5], legs: [[-4.5, -7], [4, 7]], flap: 8, tail: 2 }),
    demon(walk),
    demon({ off: [-4, 6], main: [10, 3], legs: [[-1, 2], [1.5, -1.5]], flap: -6, tail: -1 }),
    demon(walk),
  ],
  // Wings flung up and a claw raised, then a lunge with the fire hand thrown forward.
  acting: [
    demon({ t: -1.5, off: [-8.5, 3], main: [8, -11], legs: [[-5, -7], [3, 4]], flap: -12, tail: 2 }),
    demon({ t: 1.5, off: [-4.5, 5], main: [11.5, -2], legs: [[-4, -7], [5, 9]], flap: 12, tail: -1 }),
  ],
  'acted-upon': [
    demon({ t: -2, off: [-11, -3], main: [6, -7], legs: [[-5.5, -7.5], [2, 3]], flap: -10, tail: 3 }),
    demon({ t: -3, b: 1, squat: 1, off: [-12, 6], main: [3, 4], legs: [[-6, -8], [1, 3]], flap: 25, tail: -1 }),
  ],
}, {
  fit: true,
  slots: {
    body: { paints: 'the skin', large: true },
    accent: { paints: 'the eyes and claws' },
    detail: { paints: 'the wings, horns and tail barb', large: true },
    outline: { paints: 'the dark edge' },
  },
  sample: { body: '#a24a38', accent: '#f2b84a', detail: '#9a5a7a', outline: '#1d0a08' },
});
