// Draws categories/giant.svg: `npm run draw -- giant`.
// An ogre or troll facing right, filling the box: hunched, with a high round back and wide shoulders (body), a
// small head (detail, skin) thrust forward and low between them, tusks (accent), huge bar arms hanging to the
// knees, short thick legs and a knobbed club (accent). Anchors: `head` (helms), `hand.main` and `hand.off`
// (with the forearm's angle), `hip.main`, `back` and `impact` (above the head).
import { sheet, S, f, bar, held, limbs } from './kit.mjs';

const pt = (x, y) => `${f(x)},${f(y)}`;
const deg = (a, b) => (Math.atan2(b[1] - a[1], b[0] - a[0]) * 180) / Math.PI;

/**
 * t: shift right; b: bob down; lean: the head and shoulders pitched forward (x); legs: [hipx, footx] of back
 * and front leg; off, main: the hands; club: its angle in degrees (0 = right, -90 = up) and len its length;
 * duck: how far the head sinks into the shoulders.
 */
function giant({ t = 0, b = 0, lean = 0, duck = 0, legs = [[-4, -6], [3, 5]], off = [-8, 7], main = [8, 7],
  club = 50, len = 5.5, rise = 0 }) {
  const top = -11 + b + duck * 0.4 + rise;
  const hx = 8.5 + t + lean;
  const hy = -5.5 + b + duck;
  const offFrom = [-3 + t, -7 + b];
  const mainFrom = [2 + t + lean * 0.4, -6.5 + b];
  const bot = 7 + b;
  const legPath = legs.map(([h, ft]) => [[h + t, bot - 2], [(h + ft) / 2 + t * 0.5, 10.3], [ft, 11.8]]);
  const offArm = bar(offFrom, off, S.detail, 4.6);
  const mainArm = bar(mainFrom, main, S.detail, 4.6);
  const cl = held(main, club, len, { fill: S.accent, width: 2.6, knob: 2.6, knobFill: S.accent });
  const svg = [
    offArm.svg,
    `<circle cx="${f(off[0])}" cy="${f(off[1])}" r="2.6" fill="${S.detail}"/>`,
    limbs(legPath, S.body, 4.4),
    // hunched torso: a high round back at the left, wide shoulders, a heavy belly
    `<path d="M${pt(-9 + t, -2 + b)}Q${pt(-10 + t, top)} ${pt(-3 + t, top)}Q${pt(3 + t + lean, top)} ${pt(5.5 + t + lean, -3 + b)}`
      + `Q${pt(7 + t, 4 + b)} ${pt(5 + t, bot)}L${pt(-6 + t, bot)}Q${pt(-11 + t, 4 + b)} ${pt(-9 + t, -2 + b)}Z" fill="${S.body}"/>`,
    `<rect x="${f(-6 + t)}" y="${f(bot - 3)}" width="11" height="2" fill="${S.accent}" stroke="none"/>`,
    // small head thrust forward, tusks and an eye
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="3.8" fill="${S.detail}"/>`,
    `<circle cx="${f(hx + 2)}" cy="${f(hy - 1)}" r="1" fill="${S.accent}" stroke="none"/>`,
    `<polygon points="${pt(hx + 2.5, hy + 2)} ${pt(hx + 4.6, hy + 0.2)} ${pt(hx + 4.4, hy + 3.4)}" fill="${S.accent}" stroke-width="1.5"/>`,
    // club and the main arm in front
    cl.svg,
    mainArm.svg,
    `<circle cx="${f(main[0])}" cy="${f(main[1])}" r="2.8" fill="${S.detail}"/>`,
  ].join('');
  return {
    svg,
    anchors: {
      head: [hx, hy - 4],
      'hand.main': [...main, mainArm.deg],
      'hand.off': [...off, offArm.deg],
      'hip.main': [5.5 + t, bot - 3],
      back: [-9 + t, -3 + b],
      impact: [hx + 1, hy - 7],
    },
  };
}

const walk = { b: -0.6, legs: [[-3, -4], [3, 4]] };
sheet('giant', {
  idle: [giant({ main: [8, 3], club: 70, len: 8 }), giant({ b: 1, duck: 1, lean: 0.5, off: [-8, 8], main: [9.5, 3], club: 90, len: 8 })],
  moving: [
    giant({ legs: [[-4, -8], [4, 8]], off: [-5, 7], main: [8, 2], club: 60, len: 8 }),
    giant({ ...walk, main: [8, 3], club: 75, len: 8 }),
    giant({ legs: [[-3, 2], [3, -2]], off: [-9, 7], main: [7, 3], club: 80, len: 8 }),
    giant({ ...walk, off: [-7, 8], main: [8, 3], club: 70, len: 8 }),
  ],
  // Club hauled up and back behind the head, then swung down and forward.
  acting: [
    giant({ t: -2, lean: -2, legs: [[-5, -8], [3, 5]], main: [2, -11], off: [-8, 3], club: -170, len: 5 }),
    giant({ t: 1, b: 1.5, lean: 1, duck: 1, legs: [[-4, -7], [4, 9]], main: [6, 0], off: [-6, 7], club: 35, len: 7 }),
  ],
  // Struck: reeling back with the head thrown up, then crouched with the arms up round the head.
  'acted-upon': [
    giant({ t: -3, lean: -3, duck: -1, legs: [[-6, -9], [2, 3]], off: [-8, 2], main: [4, 3], club: -160, len: 5 }),
    giant({ t: -2, b: 2, duck: 2, lean: 1, legs: [[-5, -7], [2, 3]], off: [-5, -2], main: [9, -7], club: -60, len: 5 }),
  ],
}, { sample: { body: '#5d5a3f', accent: '#c9b27a', detail: '#8a7a5a', outline: '#1b1a10' } });
