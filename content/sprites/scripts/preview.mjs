// Writes preview.html (git-ignored): every variant and state, animated at its
// manifest timing, at 16, 32 and 64 px, on dark and light ground.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadManifest, loadSheets, root, shownVariants } from './lib.mjs';

const manifest = loadManifest();
const sheets = Object.values(loadSheets(manifest)).map((text) => text.replace(/<\/?svg[^>]*>/g, '')).join('');
const data = JSON.stringify({ ...manifest, variants: Object.fromEntries(shownVariants(manifest)) }).replace(/</g, '\\u003c');

const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Scarcity sprites</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  body { margin: 0; font: 14px system-ui, sans-serif; background: #16130f; color: #d8cfc0; }
  h1 { font-size: 18px; margin: 16px; }
  .variant { margin: 0 16px 16px; border: 1px solid #3a342b; border-radius: 6px; overflow: hidden; }
  .variant h2 { font-size: 14px; margin: 0; padding: 6px 10px; background: #26211a; }
  .variant h2 small { color: #9a8f7e; font-weight: normal; }
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

function sprite(variant, frames, size) {
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '-16 -16 32 32');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  for (const [slot, colour] of Object.entries(variant.palette)) svg.style.setProperty('--sprite-' + slot, colour);
  const frame = use(frames[0]);
  svg.append(frame);
  for (const name of variant.overlays) svg.append(use(manifest.overlays[name]));
  return { svg, frame };
}

function stateBlock(variant, name, state) {
  const block = document.createElement('div');
  block.className = 'state';
  block.innerHTML = '<h3>' + name + (state.frameMs ? ' (' + state.frames.length + ' x ' + state.frameMs + ' ms)' : '') + '</h3>';
  for (const ground of ['dark', 'light']) {
    const row = document.createElement('div');
    row.className = 'ground ' + ground;
    for (const size of SIZES) {
      const s = sprite(variant, state.frames, size);
      row.append(s.svg);
      if (state.frames.length > 1) animated.push({ frame: s.frame, state });
    }
    block.append(row);
  }
  return block;
}

for (const [proto, variant] of Object.entries(manifest.variants)) {
  const category = manifest.categories[variant.category];
  const section = document.createElement('section');
  section.className = 'variant';
  section.innerHTML = '<h2>' + proto + ' <small>' + variant.category + (variant.overlays.length ? ' + ' + variant.overlays.join(', ') : '') + '</small></h2>';
  const states = document.createElement('div');
  states.className = 'states';
  for (const [name, state] of Object.entries(category.states)) states.append(stateBlock(variant, name, state));
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
