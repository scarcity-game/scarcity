// Draws categories/eye.svg: `npm run draw -- eye`.
// A floating ball with one huge eye looking right and a nerve trailing below,
// clear of the ground. Idle bobs and blinks; moving drifts with the nerve
// streaming behind; acting widens the eye (a gaze); struck, it is knocked
// back and squints, then shrinks and sinks.
import { sheet, S, f, limbs, tube } from './kit.mjs';

/**
 * dx, y: where the ball's centre is; r: its radius; look: how far the iris
 * sits right of centre; iris: iris radius; lid: how much of the eye the lid
 * covers (0 open, 1 shut); sway: how far the nerve trails to the left;
 * tip: where the nerve ends (the ground is at 11).
 */
function eye({ dx = 0, y = -5, r = 9, look = 3.5, iris = 5, lid = 0, sway = 3, tip = 8 }) {
  const ix = dx + look;
  const nerveTop = y + r - 2;
  const nerve = tip - nerveTop;
  const tail = [[dx, nerveTop], [dx - sway * 0.4, nerveTop + nerve * 0.4], [dx - sway, nerveTop + nerve * 0.75],
    [dx - sway * 0.5, nerveTop + nerve]];
  const stalks = '';
  const nerveShape = `<path d="${tube(tail, [6, 4.5, 3, 1.5])}" fill="${S.detail}"/>`;
  const ball = `<circle cx="${f(dx)}" cy="${f(y)}" r="${f(r)}" fill="${S.body}"/>`;
  const veins = `<path d="M${f(dx - r + 2)} ${f(y + 1.5)}L${f(dx - r * 0.6)} ${f(y - 0.5)}L${f(dx - r * 0.55)} ${f(y - 4)}`
    + `M${f(dx - r * 0.35)} ${f(y + r - 2)}L${f(dx - r * 0.2)} ${f(y + r * 0.6)}" fill="none" stroke="${S.detail}" stroke-width="1.5"/>`;
  const iride = `<circle cx="${f(ix)}" cy="${f(y)}" r="${f(iris)}" fill="${S.accent}"/>`
    + `<circle cx="${f(ix + 0.8)}" cy="${f(y)}" r="${f(iris * 0.45)}" fill="${S.outline}" stroke="none"/>`;
  // The lid is a cap of the detail colour, lowered over the top of the eye.
  const lidY = y - r + (r * 2 * lid);
  const lidShape = lid
    ? `<path d="M${f(dx - r + 0.5)} ${f(y - 2)}C${f(dx - r + 0.5)} ${f(y - r * 0.9)} ${f(dx - r * 0.5)} ${f(y - r + 0.5)} ${f(dx)} ${f(y - r + 0.5)}`
      + `C${f(dx + r * 0.5)} ${f(y - r + 0.5)} ${f(dx + r - 0.5)} ${f(y - r * 0.9)} ${f(dx + r - 0.5)} ${f(y - 2)}`
      + `Q${f(dx)} ${f(lidY + 2)} ${f(dx - r + 0.5)} ${f(y - 2)}Z" fill="${S.detail}"/>`
    : '';
  return {
    svg: [stalks, nerveShape, ball, veins, iride, lidShape].join(''),
    anchors: { impact: [dx + 1, Math.max(y - r - 2, -15)], head: [dx, y - r + 1] },
  };
}

sheet('eye', {
  idle: [eye({}), eye({ y: -6, lid: 0.55, sway: 4 })],
  moving: [
    eye({ dx: -2, y: -4, sway: 5 }),
    eye({ dx: 0, y: -6, sway: 7, look: 4 }),
    eye({ dx: 2, y: -4, sway: 5 }),
    eye({ dx: 0, y: -3, sway: 2, look: 3 }),
  ],
  acting: [eye({ lid: 0.4, look: 3, y: -4, r: 9.5, iris: 4.5 }), eye({ iris: 7.5, look: 2, y: -5, r: 10, sway: 5 })],
  'acted-upon': [
    eye({ dx: -3, y: -5, lid: 0.7, look: 2, sway: 6 }),
    eye({ dx: 0, y: -1, r: 8.5, iris: 4, lid: 0.45, look: 1, sway: 1, tip: 9.5 }),
  ],
}, { sample: { body: '#d6c8b4', accent: '#4f8ac0', detail: '#9a4a40', outline: '#1d1612' } });
