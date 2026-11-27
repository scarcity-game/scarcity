// Draws categories/arachnid.svg: `npm run draw -- arachnid`.
// A spider facing right: big round abdomen behind, head in front with fangs,
// eight legs splayed four to a side with the knees up and out; the far pair of
// each side is darker, shorter and behind the body.
import { sheet, S, f, star, limbs } from './kit.mjs';

/** A leg: hip, a high knee, a foot on the ground; `lift` raises knee and foot. */
const leg = (hip, [kx, ky], [fx, fy], lift = 0) => [hip, [kx, ky - lift], [fx, fy - lift * 1.5]];

function spider({ dx = 0, dy = 0, rear = 0, lift = [0, 0], fangs = 0, crouch = 0, hit = 0 }) {
  const p = (px, py) => [px + dx, py + dy];
  // crouch drops the body and flattens the knees towards the ground, feet stay put.
  const k = (px, py) => p(px * (1 + crouch * 0.06), py + crouch * 2);
  const back = p(0, 2 + crouch);
  const front = p(2, 2 - rear + crouch);
  // lift[0] raises legs 1 and 3 of each side, lift[1] legs 2 and 4 (alternating tetrapods).
  // The near back legs come out from under the abdomen, so its round shape stays whole.
  const nearBack = [
    leg(back, k(-11, -4), p(-13, 10), lift[0]),
    leg(back, k(-6, -8), p(-8, 11), lift[1]),
  ];
  const nearFront = [
    leg(front, k(6, -7 - rear), p(7, 11 - rear * 0.5), lift[0] + rear),
    leg(front, k(12, -3 - rear * 1.5), p(13, 9 - rear * 1.5), lift[1] + rear),
  ];
  const far = [
    leg(back, k(-9, -5), p(-11, 8), lift[1]),
    leg(back, k(-2, -7), p(-3, 9), lift[0]),
    leg(front, k(9, -6 - rear), p(10, 9), lift[1] + rear),
    leg(front, k(3, -8 - rear), p(3, 8), lift[0] + rear),
  ];
  const [ax, ay] = p(-4, 2 + crouch);
  const [hx, hy] = p(5.5, 4 - rear + crouch * 1.2);
  return [
    // Every leg behind the body is one detail-coloured pass: darker, so it reads as the far side.
    limbs([...far, ...nearBack], S.detail, 2),
    `<ellipse cx="${f(ax)}" cy="${f(ay)}" rx="8" ry="6.5" fill="${S.body}"/>`,
    `<path d="M${f(hx + 1)} ${f(hy + 2)}L${f(hx + 2 + fangs)} ${f(hy + 6 + fangs * 0.5)}L${f(hx + 4)} ${f(hy + 1)}Z" fill="${S.detail}"/>`,
    limbs(nearFront, S.body, 2),
    `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="4.5" ry="3.8" fill="${S.body}"/>`,
    // The accent marks share one group: the hourglass on the abdomen and two eyes.
    `<g fill="${S.accent}" stroke="none">`
      + `<path d="M${f(ax - 2)} ${f(ay - 3.5)}H${f(ax + 2)}L${f(ax - 2)} ${f(ay + 1.5)}H${f(ax + 2)}Z"/>`
      + `<circle cx="${f(hx + 2)}" cy="${f(hy - 1.2)}" r="1.2"/><circle cx="${f(hx - 0.5)}" cy="${f(hy - 1.6)}" r="1"/></g>`,
    hit ? star(hx + 1, hy - 9, hit) : '',
  ].join('');
}

const up = 2;
sheet('arachnid', {
  idle: [spider({}), spider({ dy: 0.5 })],
  moving: [
    spider({ lift: [up, 0] }),
    spider({ dx: 0.5 }),
    spider({ dx: 1, lift: [0, up] }),
    spider({ dx: 0.5 }),
  ],
  acting: [spider({ rear: 1.5 }), spider({ rear: 2.5, dx: 0.5, fangs: 1.5 })],
  // Struck: it jolts back with the front legs flung up, then cowers flat as the star fades.
  'acted-upon': [spider({ dx: -1, rear: 2.5, hit: 5 }), spider({ crouch: 2, hit: 3 })],
}, { sample: { body: '#6b5a48', accent: '#c43d2e', detail: '#43372b', outline: '#17120d' } });
