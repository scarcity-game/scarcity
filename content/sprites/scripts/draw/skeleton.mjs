// Draws categories/skeleton.svg: `npm run draw -- skeleton`.
// A humanoid of bones facing right: a round skull with two glowing sockets (accent), a spine with three
// wide ribs and gaps between them, a pelvis bar, chunky bone limbs and a rusty blade in the main hand.
// The gaps through the ribcage and the skull on a bare neck are what set it apart from `humanoid` (a
// solid body) and `spectre` (hooded, legless). Anchors as in humanoid.mjs: `head` is the skull's crown.
import { sheet, S, f, bar, held } from './kit.mjs';

const BONE = 3;
const joint = ([x, y], r = 1.9) => `<circle cx="${f(x)}" cy="${f(y)}" r="${r}" fill="${S.body}"/>`;

/**
 * t: the torso's shift right; b: its drop; squat: shorter from the top. legs: [hip, foot] x of the back and
 * front leg. off, main: hands. blade: the weapon's angle (deg, clockwise from right; -65 points up and forward).
 */
function bones({ t = 0, b = 0, squat = 0, legs = [[-3, -3], [3, 3]], off = [-7, 4], main = [9, 4], blade = -60, tilt = 0 }) {
  const top = -5 + b + squat;
  const [hx, hy] = [1 + t + tilt, -9 + b + squat];
  const shoulder = -2.5 + b + squat;
  const leg = ([hip, foot]) => {
    const knee = [(hip + foot) / 2 + 1, 9.5];
    const thigh = bar([hip + t * 0.3, 5.5 + b], knee, S.body, BONE);
    const shin = bar(knee, [foot, 13], S.body, BONE);
    return thigh.svg + shin.svg + `<rect x="${f(foot - 1.5)}" y="12" width="6" height="2.5" fill="${S.body}"/>`;
  };
  const offArm = bar([-3.5 + t, shoulder], off, S.body, 2.8);
  const mainArm = bar([4.5 + t, shoulder], main, S.body, 2.8);
  const sword = held(main, blade, 7, { fill: S.detail, width: 2.4, knob: 0 });
  const ribs = [top + 1.2, top + 5.6].map((y, i) =>
    `<rect x="${f(-5 + t + (i === 1 ? -0.5 : 0))}" y="${f(y)}" width="${i === 1 ? 11.5 : 10.5}" height="2.4" fill="${S.body}"/>`).join('');
  const svg = [
    offArm.svg, joint(off, 2),
    leg(legs[0]), leg(legs[1]),
    `<rect x="${f(-1.3 + t)}" y="${f(top)}" width="2.6" height="${f(10 - top + b)}" fill="${S.body}"/>`,
    ribs,
    `<rect x="${f(-4 + t * 0.6)}" y="${f(4.5 + b)}" width="9" height="3" fill="${S.body}"/>`,
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="5" fill="${S.body}"/>`,
    `<rect x="${f(hx - 2.2)}" y="${f(hy + 3.6)}" width="6" height="2.6" fill="${S.body}" stroke="none"/>`,
    `<circle cx="${f(hx + 2.7)}" cy="${f(hy - 0.8)}" r="1.5" fill="${S.accent}" stroke="none"/>`,
    `<circle cx="${f(hx - 1.6)}" cy="${f(hy - 0.8)}" r="1.4" fill="${S.accent}" stroke="none"/>`,
    sword.svg, mainArm.svg, joint(main, 2),
  ].join('');
  return {
    svg,
    anchors: {
      head: [hx, hy - 3.5],
      'hand.main': [...main, blade],
      'hand.off': [...off, offArm.deg],
      'hip.main': [6 + t, 5 + b],
      back: [-4 + t, shoulder + 1],
      impact: [hx + 3, hy - 5.5],
    },
  };
}

const walk = { b: -1, off: [-7, 3], main: [8, 3], legs: [[-2, -2], [2.5, 2.5]] };
sheet('skeleton', {
  idle: [bones({}), bones({ b: 0.8, off: [-7, 5.6], main: [8, 5.6], blade: -50 })],
  moving: [
    bones({ off: [-8, 3], main: [4, 5.5], legs: [[-4.5, -7], [4, 7]] }),
    bones(walk),
    bones({ off: [-4, 6], main: [9, 3], legs: [[-1, 2], [1.5, -1.5]] }),
    bones(walk),
  ],
  acting: [
    bones({ t: -1.5, off: [-8.5, 3], main: [-6, -5], blade: -125, legs: [[-5, -7], [3, 4]] }),
    bones({ t: 0.5, off: [-4.5, 5], main: [6.5, -2], blade: -25, legs: [[-4, -7], [5, 8]] }),
  ],
  'acted-upon': [
    bones({ t: -2, tilt: -1, off: [-10, -3], main: [8, -5], blade: -60, legs: [[-5.5, -7.5], [2, 3]] }),
    bones({ t: -3, b: 1, squat: 1, tilt: -1, off: [-12, 6], main: [3, 5], blade: -30, legs: [[-6, -8], [1, 3]] }),
  ],
}, { sample: { body: '#d8d0bc', accent: '#5fd0c0', detail: '#8a8270', outline: '#1d1a14' } });
