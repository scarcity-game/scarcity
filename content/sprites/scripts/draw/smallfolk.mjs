// Draws categories/smallfolk.svg: `npm run draw -- smallfolk`.
// A kobold or gnome facing right, half the height of the box: a huge head (detail, skin) with long ears
// and a big nose sitting right on a small round tunic (body), stubby bandy legs, and a crude knobbed
// club (accent) in the main hand. Anchors: `head` (hats, hoods), `hand.main` and `hand.off` (with the
// forearm's angle), `hip.main`, `back` and `impact` (the effect icon, above and ahead of the head).
import { sheet, S, f, frame, limbs, leaf } from './kit.mjs';

const pt = (x, y) => `${f(x)},${f(y)}`;

/**
 * t: body shift right; b: bob down; squat: how far the head sinks onto the body; legs: [hipx, footx] of
 * back and front leg; off, main: the hands; club: the club's angle in degrees (0 = right, -90 = up);
 * ear: how far the ears droop (degrees), tilt: the head's lean forward (units of x).
 */
function smallfolk({ t = 0, b = 0, squat = 0, legs = [[-3, -5.5], [3, 5.5]], off = [-7, 8], main = [3, 9],
  club = -10, ear = 0, tilt = 0, len = 9 }) {
  const [hx, hy] = [t + tilt, 0 + b + squat];
  const topBody = 5 + b + squat;
  const bot = 10.5 + b;
  const clubLen = len;
  const a = (club * Math.PI) / 180;
  const knob = [main[0] + Math.cos(a) * clubLen, main[1] + Math.sin(a) * clubLen];
  const dArm = (from, to) => (Math.atan2(to[1] - from[1], to[0] - from[0]) * 180) / Math.PI;
  const offFrom = [-4 + t, topBody + 2];
  const mainFrom = [4.5 + t, topBody + 2];
  const legPath = legs.map(([h, ft]) => [[h + t, bot - 1], [(h + ft) / 2 + t * 0.5, 11.8], [ft, 12.8]]);
  const svg = [
    // back ear and the off arm sit behind the body
    `<path d="${leaf(hx - 3, hy - 1, 9, 4.4, 200 - ear)}" fill="${S.detail}"/>`,
    limbs([[offFrom, off]], S.body, 3),
    `<circle cx="${f(off[0])}" cy="${f(off[1])}" r="2" fill="${S.detail}"/>`,
    limbs(legPath, S.body, 3.2),
    `<path d="M${pt(-6 + t, topBody + 3)}Q${pt(-7 + t, bot + 1)} ${pt(0 + t, bot + 1)}Q${pt(8 + t, bot + 1)} ${pt(6.5 + t, topBody + 3)}`
      + `Q${pt(5 + t, topBody - 1)} ${pt(0 + t, topBody - 1)}Q${pt(-5 + t, topBody - 1)} ${pt(-6 + t, topBody + 3)}Z" fill="${S.body}"/>`,
    `<rect x="${f(-5.5 + t)}" y="${f(bot - 3.5)}" width="12" height="2" fill="${S.accent}" stroke="none"/>`,
    // head: front ear, skull, nose, eye
    `<path d="${leaf(hx + 0, hy - 4, 9, 4.2, -75 + ear)}" fill="${S.detail}"/>`,
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="6.2" fill="${S.detail}"/>`,
    `<circle cx="${f(hx + 6.5)}" cy="${f(hy + 1.5)}" r="2.6" fill="${S.detail}"/>`,
    `<circle cx="${f(hx + 3)}" cy="${f(hy - 1.5)}" r="1.4" fill="${S.accent}" stroke="none"/>`,
    `<circle cx="${f(hx + 3.4)}" cy="${f(hy - 1.5)}" r="0.7" fill="${S.outline}" stroke="none"/>`,
    // club in front, then the hand on it
    limbs([[mainFrom, main]], S.body, 3),
    limbs([[main, knob]], S.accent, 2.4),
    `<circle cx="${f(knob[0])}" cy="${f(knob[1])}" r="2.9" fill="${S.accent}"/>`,
    `<circle cx="${f(main[0])}" cy="${f(main[1])}" r="2" fill="${S.detail}"/>`,
  ].join('');
  return {
    svg,
    anchors: {
      head: [hx, hy - 6.5],
      'hand.main': [...main, dArm(mainFrom, main)],
      'hand.off': [...off, dArm(offFrom, off)],
      'hip.main': [7 + t, bot - 3],
      back: [-6 + t, topBody + 3],
      impact: [hx + 4, hy - 9],
    },
  };
}

const walk = { b: -1, legs: [[-2, -3], [2, 3.5]] };
sheet('smallfolk', {
  idle: [smallfolk({}), smallfolk({ b: 1.2, tilt: 0.5, ear: 15, off: [-7, 9], main: [3, 9], club: -22 })],
  moving: [
    smallfolk({ legs: [[-3, -7], [3, 7]], off: [-8, 6], main: [3, 9], club: -25 }),
    smallfolk({ ...walk }),
    smallfolk({ legs: [[-2, 1], [2, -2]], off: [-5, 9], main: [3, 8], club: -3, len: 8 }),
    smallfolk({ ...walk, club: -20 }),
  ],
  // Club raised high behind the head, then smashed down and forward.
  acting: [
    smallfolk({ t: -1.5, tilt: -1, legs: [[-4, -7], [2, 4]], main: [4, -3], off: [-7, 5], club: -115, len: 7 }),
    smallfolk({ t: 2, tilt: 2, b: 1, legs: [[-4, -7], [4, 9]], main: [6, 7], off: [-5, 8], club: 40, len: 7 }),
  ],
  // Struck: knocked back with ears flung, then cowering low with the arms over the head.
  'acted-upon': [
    smallfolk({ t: -3, tilt: -1, legs: [[-5, -8], [2, 3]], off: [-8, 0], main: [4, 4], club: -175, ear: -25, len: 8 }),
    smallfolk({ t: -2, b: 1, squat: 2, tilt: 1, legs: [[-5, -7], [1, 2]], off: [-6, 4], main: [4, 3], club: 95, ear: 40, len: 7 }),
  ],
}, { sample: { body: '#7a6244', accent: '#b0532e', detail: '#7f9a4a', outline: '#1e1a10' } });
