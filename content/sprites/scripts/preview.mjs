// Writes preview.html (git-ignored): every appearance and state, animated at its
// manifest timing, at 16, 32 and 64 px, on dark and light ground.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadManifest, loadSheets, root, shownAppearances } from './lib.mjs';

const manifest = loadManifest();
const sheets = Object.values(loadSheets(manifest)).map((text) => text.replace(/<\/?svg[^>]*>/g, '')).join('');
const data = JSON.stringify({ ...manifest, appearances: Object.fromEntries(shownAppearances(manifest)) }).replace(/</g, '\\u003c');

const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Scarcity sprites</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  body { margin: 0; font: 14px system-ui, sans-serif; background: #16130f; color: #d8cfc0; }
  h1 { font-size: 18px; margin: 16px; }
  .appearance { margin: 0 16px 16px; border: 1px solid #3a342b; border-radius: 6px; overflow: hidden; }
  .appearance h2 { font-size: 14px; margin: 0; padding: 6px 10px; background: #26211a; }
  .appearance h2 small { color: #9a8f7e; font-weight: normal; }
  .states { display: flex; flex-wrap: wrap; }
  .state { padding: 8px 12px; }
  .state h3 { font-size: 11px; font-weight: normal; margin: 0 0 4px; color: #9a8f7e; }
  .ground { display: flex; align-items: flex-end; gap: 8px; padding: 6px; }
  .dark { background: #16130f; }
  .light { background: #d9d2c3; }
</style></head><body>
<h1>Scarcity sprites</h1>
<svg width="0" height="0" style="position:absolute" aria-hidden="true">${sheets}</svg>
<div id="root"></div>
<script>
const manifest = ${data};
const SIZES = [16, 32, 64];
const NS = 'http://www.w3.org/2000/svg';
const animated = [];

function use(id) {
  const u = document.createElementNS(NS, 'use');
  u.setAttribute('href', '#' + id);
  return u;
}

// An anchored overlay moves from its "at" to the anchor (as lib.mjs overlayTransform and the client); the preview
// uses the first frame of a state, the client each frame.
function overlayUse(overlay, anchors) {
  if (typeof overlay === 'string') return use(overlay);
  const a = anchors[overlay.anchor];
  if (!a) return null;
  const g = document.createElementNS(NS, 'g');
  const deg = (a[2] || 0) - (overlay.at[2] || 0);
  g.setAttribute('transform', 'translate(' + a[0] + ' ' + a[1] + ') rotate(' + deg + ') translate(' + -overlay.at[0] + ' ' + -overlay.at[1] + ')');
  g.append(use(overlay.symbol));
  return g;
}

function sprite(appearance, frames, size, anchors, stateName) {
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '-16 -16 32 32');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  for (const [slot, colour] of Object.entries(appearance.colours)) svg.style.setProperty('--sprite-' + slot, colour);
  const frame = use(frames[0]);
  svg.append(frame);
  for (const name of appearance.overlays) {
    const o = overlayUse(manifest.overlays[name], anchors[0] || {});
    if (o) svg.append(o);
  }
  if (stateName === 'acted-upon') {
    const kind = manifest.effects.kinds[manifest.effects.default];
    const at = (anchors[0] || {}).impact || [0, -8];
    const g = document.createElementNS(NS, 'g');
    g.setAttribute('transform', 'translate(' + at[0] + ' ' + at[1] + ')');
    for (const [slot, colour] of Object.entries(kind.colours)) g.style.setProperty('--sprite-' + slot, colour);
    g.append(use(kind.symbol));
    svg.append(g);
  }
  return { svg, frame };
}

function stateBlock(appearance, name, state) {
  const block = document.createElement('div');
  block.className = 'state';
  block.innerHTML = '<h3>' + name + (state.frameMs ? ' (' + state.frames.length + ' x ' + state.frameMs + ' ms)' : '') + '</h3>';
  for (const ground of ['dark', 'light']) {
    const row = document.createElement('div');
    row.className = 'ground ' + ground;
    for (const size of SIZES) {
      const s = sprite(appearance, state.frames, size, state.anchors || [], name);
      row.append(s.svg);
      if (state.frames.length > 1) animated.push({ frame: s.frame, state });
    }
    block.append(row);
  }
  return block;
}

for (const [proto, appearance] of Object.entries(manifest.appearances)) {
  const category = manifest.categories[appearance.category];
  const section = document.createElement('section');
  section.className = 'appearance';
  section.innerHTML = '<h2>' + proto + ' <small>' + appearance.category + (appearance.overlays.length ? ' + ' + appearance.overlays.join(', ') : '') + '</small></h2>';
  const states = document.createElement('div');
  states.className = 'states';
  for (const [name, state] of Object.entries(category.states)) states.append(stateBlock(appearance, name, state));
  section.append(states);
  document.getElementById('root').append(section);
}

const start = performance.now();
function tick() {
  const t = performance.now() - start;
  for (const { frame, state } of animated) {
    frame.setAttribute('href', '#' + state.frames[Math.floor(t / state.frameMs) % state.frames.length]);
  }
  requestAnimationFrame(tick);
}
tick();
</script></body></html>
`;
writeFileSync(join(root, 'preview.html'), html);
console.log('wrote preview.html');
