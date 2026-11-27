// Draws categories/bird.svg: `npm run draw -- bird`.
// A bird facing right: an egg-shaped body standing upright on two thin legs, a
// round head with a beak pointing right, a tail fanned behind and a folded wing
// on the side. A peck drops the head forward; a flare lifts the wing.
import { sheet, S, f, star, limbs } from './kit.mjs';

function bird({ dx = 0, dy = 0, lean = 0, head = [0, 0], flare = 0, beak = 0, squat = 0, tail = 0, legs = [0, 0], hit = 0 }) {
  const gy = 12.5; // the ground
  // lean tilts the whole upper body forward (right); squat sinks it onto the legs.
  const bx = dx + lean * 0.5, by = dy + 2 + squat;
  const hx = dx + 3.5 + lean + head[0], hy = dy - 6.5 + squat + head[1];
  const [l0, l1] = legs;
  const leg = (x, lift) => [[x + dx, by + 5], [x + dx + 0.5, gy - lift], [x + dx + 3.5, gy - lift]];
  const beakPath = `<path d="M${f(hx + 3)} ${f(hy - 2.5 - beak)}L${f(hx + 9)} ${f(hy - 0.5 - beak)}L${f(hx + 3)} ${f(hy + 0.8)}Z`
    + (beak ? `M${f(hx + 3)} ${f(hy + 1.5)}L${f(hx + 7.5)} ${f(hy + 1 + beak)}L${f(hx + 3)} ${f(hy + 3.5)}Z` : '') + '"/>';
  const wy = by + 0.5 - flare * 0.8;
  const wing = `<path d="M${f(bx - 2)} ${f(wy - 4.5)}`
    + `C${f(bx + 5)} ${f(wy - 4.5 - flare)} ${f(bx + 6)} ${f(wy + 3)} ${f(bx - 3)} ${f(wy + 7.5)}`
    + `C${f(bx - 6)} ${f(wy + 4)} ${f(bx - 5.5)} ${f(wy - 2)} ${f(bx - 2)} ${f(wy - 4.5)}Z"/>`;
  // A flared wing throws a second, pointed feather fan up and back.
  const wingFlare = flare
    ? `<path d="M${f(bx - 1)} ${f(wy - 2)}L${f(bx - 7 - flare)} ${f(wy - 9 - flare)}L${f(bx + 3)} ${f(wy - 5)}Z"/>` : '';
  const tx = bx - 5, ty = by + 1;
  return [
    limbs([leg(-2.5, l0), leg(2.5, l1)], S.accent, 2),
    `<path d="M${f(tx)} ${f(ty - 3)}L${f(tx - 6.5)} ${f(ty + 1 - tail)}L${f(tx - 4)} ${f(ty + 4.5)}L${f(tx - 6.5)} ${f(ty + 7)}L${f(tx + 1)} ${f(ty + 5.5)}Z" fill="${S.detail}"/>`,
    `<ellipse cx="${f(bx)}" cy="${f(by)}" rx="6.5" ry="7.5" fill="${S.body}"/>`,
    `<g fill="${S.detail}">${wingFlare}${wing}</g>`,
    `<circle cx="${f(hx)}" cy="${f(hy)}" r="4.2" fill="${S.body}"/>`,
    `<g fill="${S.accent}">${beakPath}<circle cx="${f(hx + 1.2)}" cy="${f(hy - 1.3)}" r="1.1" stroke="none"/></g>`,
    hit ? star(hx + 4.5, hy - 4.5, hit * 0.8) : '',
  ].join('');
}

sheet('bird', {
  idle: [bird({}), bird({ dy: 1, head: [0.5, 1.5], tail: 1.5 })],
  moving: [
    bird({ dy: -3, lean: 1, legs: [4, 4], tail: 1 }),
    bird({ dx: 0.5, lean: 1.5 }),
    bird({ dx: 1, dy: -3, lean: 1, legs: [4, 4], tail: -1 }),
    bird({ dx: 0.5, lean: 1.5, tail: 1 }),
  ],
  acting: [
    bird({ lean: -1, head: [-1, -1], flare: 1, beak: 1.5 }),
    bird({ lean: 1, head: [1.5, 5], squat: 1, flare: 2, beak: 0 }),
  ],
  // Struck: it is thrown back with beak wide under a big star, then cowers low as the star fades.
  'acted-upon': [
    bird({ dx: -1.5, dy: 1.5, lean: -2.5, head: [-1, -1], flare: 1.5, beak: 2, legs: [2, 2], hit: 5.5 }),
    bird({ dx: 0, squat: 3, head: [-1, 2], tail: -1, hit: 4.5 }),
  ],
});
