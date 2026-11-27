// Draws categories/ooze.svg: `npm run draw -- ooze`.
// A dome of jelly spread on the floor, no limbs: a wet highlight on the upper
// left, dark bits floating inside, a lean to the right (its front). Acting
// rears a pseudopod from the front; moving stretches forward, then catches up.
import { sheet, S, f, star, limbs } from './kit.mjs';

const BASE = 11;

/**
 * The dome. w: half width; h: height; lean: how far the crown sits forward
 * (right); pod: how far the pseudopod rises from the front; hit: star size.
 */
function ooze({ dx = 0, w = 12, h = 13, lean = 2, pod = 0, hit = 0 }) {
  const l = dx - w;
  const r = dx + w;
  const cx = dx + lean;
  const top = BASE - h;
  const side = BASE - h * 0.75;
  const body = `<path d="M${f(l)} ${BASE}C${f(l - 1)} ${f(side)} ${f(cx - w * 0.5)} ${f(top)} ${f(cx)} ${f(top)}`
    + `C${f(cx + w * 0.6)} ${f(top)} ${f(r + 1)} ${f(side)} ${f(r)} ${BASE}`
    + `Q${f(dx)} ${BASE + 2} ${f(l)} ${BASE}Z" fill="${S.body}"/>`;
  // The pseudopod grows out of the front, behind the dome, so the dome stays whole.
  const finger = pod
    ? limbs([[[dx + w * 0.5, BASE - 3], [dx + w * 0.8 + pod * 0.3, BASE - 5 - pod * 0.6], [dx + w * 0.7 + pod * 0.5, BASE - 4 - pod * 1.4]]], S.body, 5)
    : '';
  const bits = `<g fill="${S.detail}" stroke="none"><circle cx="${f(cx - 4)}" cy="${f(BASE - 4)}" r="2.6"/>`
    + `<circle cx="${f(cx + 3)}" cy="${f(BASE - h * 0.45)}" r="1.8"/><circle cx="${f(cx + 6.5)}" cy="${f(BASE - 3)}" r="1.5"/></g>`;
  const shine = `<path d="M${f(cx - 7)} ${f(BASE - h * 0.4)}Q${f(cx - 6.5)} ${f(top + 3)} ${f(cx - 2)} ${f(top + 2.5)}" fill="none" stroke="${S.accent}" stroke-width="2"/>`;
  return [finger, body, bits, shine, hit ? star(cx + 3, top - 5, hit) : ''].join('');
}

sheet('ooze', {
  // It settles and swells, so the crown rises a little.
  idle: [ooze({ h: 12 }), ooze({ w: 10.5, h: 15, lean: 2.5 })],
  moving: [
    ooze({ w: 12, h: 12, lean: 3 }),
    ooze({ w: 12, h: 8, lean: 4, dx: 1 }),
    ooze({ w: 12, h: 10, lean: 4, dx: 1.5 }),
    ooze({ w: 10, h: 15, lean: 0, dx: 0.5 }),
  ],
  acting: [ooze({ pod: 5, h: 12 }), ooze({ pod: 9, h: 11, dx: -1 })],
  // Struck: it is thrown back and stands tall, then flattens as the star shrinks.
  'acted-upon': [ooze({ dx: -2, w: 10, h: 15, lean: -3, hit: 5 }), ooze({ w: 13, h: 7, lean: 0, hit: 3 })],
}, { sample: { body: '#5d8a4a', accent: '#d9e6a0', detail: '#3a5a2e', outline: '#121c0e' } });
