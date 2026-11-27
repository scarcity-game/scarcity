// Draws categories/arachnid.svg: `npm run draw -- arachnid`.
// A spider facing right: big round abdomen behind, head in front with fangs,
// eight legs splayed four to a side with the knees up and out; the far pair of
// each side is darker, shorter and behind the body.
import { sheet, S, f, star, limbs } from './kit.mjs';

/** A leg: hip, a high knee, a foot on the ground; `lift` raises knee and foot. */
const leg = (hip, [kx, ky], [fx, fy], lift = 0) => [hip, [kx, ky - lift], [fx, fy - lift * 1.5]];

function spider({ dx = 0, dy = 0, rear = 0, lift = [0, 0], fangs = 0, hit = false }) {
  const p = (px, py) => [px + dx, py + dy];
  const back = p(0, 2);
  const front = p(2, 2 - rear);
  // lift[0] raises legs 1 and 3 of each side, lift[1] legs 2 and 4 (alternating tetrapods).
  // The near back legs come out from under the abdomen, so its round shape stays whole.
  const nearBack = [
    leg(back, p(-11, -4), p(-13, 10), lift[0]),
    leg(back, p(-6, -8), p(-8, 11), lift[1]),
  ];
  const nearFront = [
    leg(front, p(6, -7 - rear), p(7, 11 - rear * 0.5), lift[0] + rear),
    leg(front, p(12, -3 - rear * 1.5), p(13, 9 - rear * 1.5), lift[1] + rear),
  ];
  const far = [
    leg(back, p(-9, -5), p(-11, 8), lift[1]),
    leg(back, p(-2, -7), p(-3, 9), lift[0]),
    leg(front, p(9, -6 - rear), p(10, 9), lift[1] + rear),
    leg(front, p(3, -8 - rear), p(3, 8), lift[0] + rear),
  ];
  const [ax, ay] = p(-4, 2);
  const [hx, hy] = p(5.5, 4 - rear);
  return [
    // Every leg behind the body is one detail-coloured pass (two paths a frame keeps the sheet under 12 KB).
    limbs([...far, ...nearBack], S.detail, 2),
    `<ellipse cx="${f(ax)}" cy="${f(ay)}" rx="8" ry="6.5" fill="${S.body}"/>`,
    `<path d="M${f(hx + 1)} ${f(hy + 2)}L${f(hx + 2 + fangs)} ${f(hy + 6 + fangs * 0.5)}L${f(hx + 4)} ${f(hy + 1)}Z" fill="${S.detail}"/>`,
    limbs(nearFront, S.body, 2),
    `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="4.5" ry="3.8" fill="${S.body}"/>`,
    // The accent marks share one group: the hourglass on the abdomen and two eyes.
    `<g fill="${S.accent}" stroke="none">`
      + `<path d="M${f(ax - 2)} ${f(ay - 3.5)}H${f(ax + 2)}L${f(ax - 2)} ${f(ay + 1.5)}H${f(ax + 2)}Z"/>`
      + `<circle cx="${f(hx + 2)}" cy="${f(hy - 1.2)}" r="1.2"/><circle cx="${f(hx - 0.5)}" cy="${f(hy - 1.6)}" r="1"/></g>`,
    hit ? star(hx, hy - 10) : '',
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
  'acted-upon': [spider({ dx: -0.5, hit: true }), spider({ dx: -1, dy: 0.8, hit: true })],
});
