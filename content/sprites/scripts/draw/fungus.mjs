// Draws categories/fungus.svg: `npm run draw -- fungus`.
// A mushroom: a broad domed cap with pale spots on a thick, rooted stem, pale
// gills under the cap's rim. No limbs; it barely moves: it breathes, shuffles
// its root-foot, puffs spores, and bends away when struck.
import { sheet, S, f, tube } from './kit.mjs';

const BASE = 14;

/**
 * lift: cap rises (breathing); sway: cap leans right (+) or left (-), bending the stem;
 * dx: whole body shift; squash: cap drops and widens; slide: root-foot shifted (shuffle);
 * puff: spores rising from the cap.
 */
function fungus({ lift = 0, sway = 0, dx = 0, squash = 0, slide = 0, puff = 0 }) {
  const capY = 1 - lift + squash * 1.5; // the gill line (cap underside)
  const cx = dx + sway;
  const w = 12 + squash * 1;
  const h = 10 - squash * 2;
  const top = capY - h;
  // Stem: from the root, bending to sit under the cap's middle.
  const stem = tube([[dx + slide * 0.4, BASE], [dx + sway * 0.4, (BASE + capY) / 2], [cx, capY + 1]], [12, 7, 8]);
  const root = `<path d="M${f(dx + slide - 8)} ${BASE}Q${f(dx + slide - 3)} ${f(BASE - 4)} ${f(dx + slide)} ${f(BASE - 4)}`
    + `Q${f(dx + slide + 3)} ${f(BASE - 4)} ${f(dx + slide + 8)} ${BASE}Z" fill="${S.detail}"/>`;
  const gills = `<path d="M${f(cx - w + 1)} ${f(capY)}Q${f(cx)} ${f(capY + 5.5)} ${f(cx + w - 1)} ${f(capY)}Z" fill="${S.detail}"/>`;
  const cap = `<path d="M${f(cx - w)} ${f(capY)}C${f(cx - w)} ${f(top - 1)} ${f(cx - w * 0.5)} ${f(top)} ${f(cx)} ${f(top)}`
    + `C${f(cx + w * 0.5)} ${f(top)} ${f(cx + w)} ${f(top - 1)} ${f(cx + w)} ${f(capY)}`
    + `Q${f(cx)} ${f(capY + 2.5)} ${f(cx - w)} ${f(capY)}Z" fill="${S.body}"/>`;
  const spots = `<g fill="${S.accent}" stroke="none"><circle cx="${f(cx - 5)}" cy="${f(top + 4.5)}" r="2.2"/>`
    + `<circle cx="${f(cx + 2)}" cy="${f(top + 3.5)}" r="2"/><circle cx="${f(cx + 7)}" cy="${f(top + 6)}" r="1.9"/>`
    + `<circle cx="${f(cx - 1)}" cy="${f(top + 8)}" r="1.5"/></g>`;
  let spores = '';
  if (puff) {
    const r = 1.6 + puff * 0.4;
    spores = `<g fill="${S.accent}" stroke="none"><circle cx="${f(cx - 6)}" cy="${f(top - 1.5 - puff)}" r="${f(r)}"/>`
      + `<circle cx="${f(cx + 3)}" cy="${f(top - 2.5 - puff * 1.2)}" r="${f(r + 0.3)}"/>`
      + `<circle cx="${f(cx + 8)}" cy="${f(top - 1.5 - puff)}" r="${f(r)}"/></g>`;
  }
  const svg = [root, `<path d="${stem}" fill="${S.detail}"/>`, gills, cap, spots, spores].join('');
  return {
    svg,
    anchors: {
      impact: [cx + 3, top - 2],
      head: [cx + 5, top + 4],
      back: [cx - 8, top + 5],
    },
  };
}

sheet('fungus', {
  idle: [fungus({}), fungus({ lift: 2, squash: -0.3 })],
  moving: [
    fungus({ slide: 3, sway: 2.5, lift: 1 }),
    fungus({ dx: 1, squash: 1.2 }),
    fungus({ dx: 2, slide: -3, sway: -2, lift: 1 }),
    fungus({ dx: 1, squash: 1.2 }),
  ],
  acting: [fungus({ squash: 1.2, puff: 0.5 }), fungus({ lift: 0.5, puff: 1 })],
  // Struck: the cap is knocked back, then the whole mushroom cowers, squashed flat.
  'acted-upon': [fungus({ dx: 0, sway: -3, lift: 0.5 }), fungus({ squash: 1.6, sway: 1, dx: -1 })],
}, { sample: { body: '#8a4a3a', accent: '#e6d6b0', detail: '#cbbfa6', outline: '#1d120e' } });
