// Draws categories/lizard.svg: `npm run draw -- lizard`.
// A lizard facing right: a long low body near the ground, a wedge head, four
// short splayed legs (the far pair darker, behind the body) and a tail that
// tapers away behind and swings with the step. Acting opens the jaw.
import { sheet, S, f, limbs, tube } from './kit.mjs';

/** A leg: shoulder, an elbow out to the side, a foot on the ground; `lift` raises the foot. */
const leg = (hip, elbow, foot, lift = 0) => [hip, [elbow[0], elbow[1] - lift * 0.6], [foot[0], foot[1] - lift]];

function lizard({ dx = 0, dy = 0, lift = [0, 0], sway = 0, jaw = 0, rear = 0, flat = 0 }) {
  const y = 3 + dy + flat * 2.5;
  const p = (px, py) => [px + dx - 0.8, py + y];
  const [bx, by] = p(-1, 0);
  const [hx, hy] = p(7.5 - rear * 0.5 + flat * -1, -1 - rear + flat * 2.5);
  const tail = [p(-5, 1), p(-10, 3 + sway * 0.5), p(-14, 3.5 + sway)];
  const g = 6 - dy - flat * 2.5; // ground line relative to y
  const farLegs = [
    leg(p(4, 1), p(7, 3), p(8, g - 0.5), lift[1]),
    leg(p(-4, 1), p(-6, 3), p(-7, g - 0.5), lift[0]),
  ];
  const nearLegs = [
    leg(p(3, 2), p(6, 4.5), p(5, g), lift[0]),
    leg(p(-4, 2), p(-6, 4.5), p(-3, g), lift[1]),
  ];
  const svg = [
    limbs(farLegs, S.detail, 2),
    `<path d="${tube(tail, [5, 3.2, 0.8])}" fill="${S.body}"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="7.5" ry="4.2" fill="${S.body}"/>`,
    // The belly strip and a row of back spines.
    `<path d="M${f(bx - 5)} ${f(by + 2.2)}Q${f(bx)} ${f(by + 4.2)} ${f(bx + 5)} ${f(by + 2.2)}" fill="none" stroke="${S.detail}" stroke-width="1.5"/>`,
    `<path d="M${f(bx - 5)} ${f(by - 3.6)}L${f(bx - 4.5)} ${f(by - 6)}L${f(bx - 3)} ${f(by - 4)}L${f(bx - 1)} ${f(by - 6.4)}L${f(bx + 0.5)} ${f(by - 4.3)}L${f(bx + 2.5)} ${f(by - 6)}L${f(bx + 3.5)} ${f(by - 3.5)}Z" fill="${S.accent}"/>`,
    limbs(nearLegs, S.detail, 2),
    // Lower jaw (dropped by `jaw`), then the wedge head.
    jaw ? `<path d="M${f(hx - 1)} ${f(hy + 1.5)}L${f(hx + 6.5)} ${f(hy + 1.5 + jaw * 2.2)}L${f(hx + 6)} ${f(hy + 1)}Z" fill="${S.detail}"/>` : '',
    `<path d="M${f(hx - 3.5)} ${f(hy - 2.5)}Q${f(hx)} ${f(hy - 4.2)} ${f(hx + 3)} ${f(hy - 2)}L${f(hx + 8)} ${f(hy + 0.8)}L${f(hx + 7)} ${f(hy + 2.2)}L${f(hx - 3)} ${f(hy + 3.5)}Z" fill="${S.body}"/>`,
    `<circle cx="${f(hx + 1)}" cy="${f(hy - 1)}" r="1.2" fill="${S.accent}" stroke="none"/>`,
  ].join('');
  return {
    svg,
    anchors: { impact: [hx, hy - 5], head: [hx + 1, hy], back: [bx, by - 4], 'hip.main': [bx - 3, by + 2] },
  };
}

const up = 2.5;
sheet('lizard', {
  idle: [lizard({}), lizard({ rear: 1.2, sway: 1 })],
  moving: [
    lizard({ lift: [up, 0], sway: 2 }),
    lizard({ dx: 0.5, sway: 0 }),
    lizard({ dx: 0.4, lift: [0, up], sway: -2 }),
    lizard({ dx: 0.5, sway: 0 }),
  ],
  acting: [lizard({ rear: 1.5, dx: -0.3 }), lizard({ rear: 0, jaw: 4.5, dx: 0.4, sway: -1 })],
  // Struck: it is thrown back with the head up, then lies flat.
  'acted-upon': [lizard({ rear: 3, dx: -0.3, dy: -0.5, sway: 2 }), lizard({ flat: 1, dx: -0.3 })],
}, { sample: { body: '#6f7448', accent: '#d1a43a', detail: '#4c4f30', outline: '#1b1c10' } });
