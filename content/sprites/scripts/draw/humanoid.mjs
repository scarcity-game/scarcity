// Draws categories/humanoid.svg: `npm run draw -- humanoid`.
// A person facing right: a big round head with hair and an eye, a tunic with a
// belt, arms and legs in their own colour, the off arm behind the body and the
// main arm in front. Each part has its own slot (`SLOTS`), so an appearance can
// dress it. Anchors name where gear goes: `head` (headgear), `hand.main` and
// `hand.off` (held things, with the forearm's angle), `hip.main` (a hung lantern
// or sheath), `back` (a pack or quiver) and `impact` (the effect icon).
import { sheet, paints, f, bar } from './kit.mjs';

const SLOTS = {
  skin: { paints: 'the face and hands' },
  hair: { paints: 'the hair on the top and back of the head' },
  eyes: { paints: 'the eye' },
  primary: { paints: 'the tunic', large: true },
  secondary: { paints: 'the belt, and the trim of worn gear (a hood, a lantern)' },
  limbs: { paints: 'the sleeves and legs', large: true },
  outline: { paints: 'the dark edge' },
};
/** Fallback colours, and the sample the sheet view draws it in: the player's. */
const SAMPLE = {
  skin: '#e0b894', hair: '#4a3626', eyes: '#231b14', primary: '#7d8573',
  secondary: '#a8895a', limbs: '#646b5c', outline: '#231b14',
};
const P = paints(SAMPLE);

/** A leg from the hip (top at y = top) to a foot on the ground pointing right. */
const leg = ([hip, foot], top) => `<polygon points="${f(hip - 2.2)},${f(top)} ${f(hip + 2.2)},${f(top)} `
  + `${f(foot + 2.2)},12 ${f(foot + 4.5)},14 ${f(foot - 2.2)},14" fill="${P.limbs}"/>`;

const hand = ([x, y]) => `<circle cx="${f(x)}" cy="${f(y)}" r="2" fill="${P.skin}"/>`;

/**
 * Hair on a head of radius 4.5 at (x, y): the crown and the back of the head, the face (front, lower) left
 * bare. It stays inside the head, so it changes the colours, not the silhouette.
 */
function hair(x, y) {
  const r = 4.5;
  const rim = [175, 200, 225, 250, 275, 300].map((deg) => {
    const a = (deg * Math.PI) / 180;
    return [x + r * Math.cos(a), y + r * Math.sin(a)];
  });
  const points = [...rim, [x + 0.6, y - 2.6], [x - 2.4, y - 0.8]];
  return `<polygon points="${points.map(([px, py]) => `${f(px)},${f(py)}`).join(' ')}" fill="${P.hair}"/>`;
}

/**
 * t: the torso's shift right (a lean); b: how far the torso drops (a bob); squat: how much shorter it gets
 * from the top. legs: [hip, foot] x of the back and front leg. off, main: the hands. Head and shoulders ride
 * the torso, so the head anchor and anything hung on it follow the pose.
 */
function person({ t = 0, b = 0, squat = 0, legs = [[-3.5, -3.5], [3.5, 3.5]], off = [-7, 4], main = [9, 4] }) {
  const top = -5 + b + squat;
  const [hx, hy] = [1 + t, -8 + b + squat];
  const shoulder = -2 + b + squat;
  // An arm is a bar from shoulder to hand; its angle goes on the hand anchor for held gear.
  const offArm = bar([-4 + t, shoulder], off, P.limbs);
  const mainArm = bar([5 + t, shoulder], main, P.limbs);
  const svg = [
    offArm.svg, hand(off),
    leg(legs[0], 5 + b), leg(legs[1], 5 + b),
    `<polygon points="${f(-5 + t)},${f(top)} ${f(6 + t)},${f(top)} ${f(7 + t)},${f(6 + b)} ${f(-6 + t)},${f(6 + b)}" fill="${P.primary}"/>`,
    `<rect x="${f(-5.8 + t)}" y="${f(2 + b + squat * 0.5)}" width="12.6" height="2" fill="${P.secondary}" stroke="none"/>`,
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="4.5" fill="${P.skin}"/>`,
    hair(hx, hy),
    `<circle cx="${f(hx + 2.6)}" cy="${f(hy - 0.6)}" r="1" fill="${P.eyes}" stroke="none"/>`,
    mainArm.svg, hand(main),
  ].join('');
  return {
    svg,
    anchors: {
      head: [hx, hy - 1.5],
      'hand.main': [...main, mainArm.deg],
      'hand.off': [...off, offArm.deg],
      'hip.main': [10 + t, 3 + b],
      back: [-5 + t, shoulder + 1],
      // The effect's icon lands up and forward of the head, clear of the face.
      impact: [hx + 3, hy - 4],
    },
  };
}

const walk = { b: -1, off: [-7, 3], main: [8, 3], legs: [[-2, -2], [2.5, 2.5]] };
sheet('humanoid', {
  idle: [person({}), person({ b: 0.8, off: [-7, 5.6], main: [9, 5.6] })],
  moving: [
    person({ off: [-8, 3], main: [4, 5.5], legs: [[-4.5, -7], [4, 7]] }),
    person(walk),
    person({ off: [-4, 6], main: [10, 3], legs: [[-1, 2], [1.5, -1.5]] }),
    person(walk),
  ],
  // Wind up overhead, then strike forward.
  acting: [
    person({ t: -1.5, off: [-8.5, 3], main: [-4.5, -13], legs: [[-5, -7], [3, 4]] }),
    person({ t: 1.5, off: [-4.5, 5], main: [13, -3], legs: [[-4, -7], [5, 9]] }),
  ],
  // Struck: thrown back with the arms flung up, then cowering low.
  'acted-upon': [
    person({ t: -2, off: [-11, -4], main: [6, -8], legs: [[-5.5, -7.5], [2, 3]] }),
    person({ t: -3, b: 1, squat: 1, off: [-12, 6], main: [3, 4], legs: [[-6, -8], [1, 3]] }),
  ],
}, { slots: SLOTS, sample: SAMPLE });
