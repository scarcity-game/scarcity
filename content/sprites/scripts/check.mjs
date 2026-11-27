// The module's test: manifest, sheets and prototype coverage. Exits 1 on any problem.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv from 'ajv';
import { XMLParser } from 'fast-xml-parser';
import { loadManifest, loadSheets, readJson, root, contrast, DARK_GROUND, MIN_SAMPLE_CONTRAST } from './lib.mjs';
import { elementProblems } from './svg-rules.mjs';

const repo = join(root, '..', '..');
const PROTOTYPE_DIRS = [
  'libs/world-model/src/main/resources/prototypes',
  'libs/world-seed/src/main/resources/seed',
];
const NO_SPRITE = new Set(['placeholder']);
/** The materials catalogue (docs/resources.md, "Materials"): each names the patterns a game may draw it with. */
const MATERIALS = 'libs/world-model/src/main/resources/materials.json';
const STATE_RULES = {
  idle: [2, 4], moving: [4, 4], acting: [2, 4], 'acted-upon': [2, 4],
};
const REDUCED_STATES = { stone: ['idle', 'moving'] };
/** Size caps per symbol: a sprite frame or overlay, and a pattern's one tile. A backstop against pasted-in art, not a style rule. */
const MAX_FRAME_BYTES = 2 * 1024;
const MAX_TILE_BYTES = 12 * 1024;
const SPRITE_BOX = '-16 -16 32 32';
const TILE_BOX = '0 0 64 64';
/** The viewBox a sheet and its symbols use: a pattern's tile, else the sprite grid. */
const boxOf = (file) => (file.startsWith('patterns/') ? TILE_BOX : SPRITE_BOX);

const problems = [];
const fail = (message) => problems.push(message);

const manifest = loadManifest();
const sheets = loadSheets(manifest);


/** A category's sample body must stand out from the dark ground, or the sprite vanishes at 16 px. */
function checkSamples() {
  for (const [name, category] of Object.entries(manifest.categories)) {
    const body = category.sample?.body;
    if (body && /^#[0-9a-f]{6}$/i.test(body) && contrast(body, DARK_GROUND) < MIN_SAMPLE_CONTRAST) {
      fail(`category ${name}: sample body ${body} has contrast ${contrast(body, DARK_GROUND).toFixed(2)} with the dark ground, need ${MIN_SAMPLE_CONTRAST}`);
    }
  }
}

function checkSchema() {
  const validate = new Ajv({ allErrors: true, strict: true }).compile(readJson(join(root, 'sprites.schema.json')));
  if (!validate(manifest)) {
    for (const e of validate.errors) fail(`sprites.json ${e.instancePath || '/'} ${e.message}`);
  }
}

const parser = new XMLParser({
  preserveOrder: true, ignoreAttributes: false, attributeNamePrefix: '', trimValues: false,
});

/** Parse a sheet; returns the list of `symbol` nodes as {id, node}. */
function parseSheet(file, text) {
  if (/<!DOCTYPE|<!ENTITY|<\?xml-stylesheet/i.test(text)) fail(`${file}: DOCTYPE, ENTITY and stylesheets are not allowed`);
  const cap = file.startsWith('patterns/') ? MAX_TILE_BYTES : MAX_FRAME_BYTES;
  for (const [symbol, id] of text.matchAll(/<symbol id="([^"]*)"[\s\S]*?<\/symbol>/g)) {
    const bytes = Buffer.byteLength(symbol);
    if (bytes > cap) fail(`${file} ${id}: ${bytes} bytes, the limit is ${cap} a symbol`);
  }
  let tree;
  try {
    tree = parser.parse(text);
  } catch (e) {
    fail(`${file}: not XML: ${e.message}`);
    return [];
  }
  const roots = tree.filter((n) => !('#text' in n) || n['#text'].trim());
  const svg = roots[0];
  if (roots.length !== 1 || !svg.svg) {
    fail(`${file}: the root must be a single <svg>`);
    return [];
  }
  const attrs = svg[':@'] ?? {};
  if (attrs.viewBox !== boxOf(file)) fail(`${file}: <svg> viewBox must be "${boxOf(file)}"`);
  const symbols = [];
  for (const child of svg.svg) {
    if ('#text' in child) {
      if (child['#text'].trim()) fail(`${file}: stray text`);
    } else if (child.symbol) {
      symbols.push({ id: child[':@']?.id, node: child });
    } else {
      fail(`${file}: only <symbol> may sit directly in <svg>, found <${Object.keys(child)[0]}>`);
    }
  }
  return symbols;
}

function checkSymbol(file, { id, node }) {
  if (!id) return fail(`${file}: a symbol has no id`);
  if (node[':@'].viewBox !== boxOf(file)) fail(`${file} ${id}: viewBox must be "${boxOf(file)}"`);
  const bounds = boxOf(file) === TILE_BOX ? { min: 0, max: 64 } : { min: -16, max: 16 };
  for (const p of elementProblems(node, bounds)) fail(`${file} ${id}: ${p}`);
}

function parseAll() {
  const found = {};
  for (const [file, text] of Object.entries(sheets)) {
    for (const symbol of parseSheet(file, text)) {
      checkSymbol(file, symbol);
      if (found[symbol.id]) fail(`${file}: duplicate symbol id ${symbol.id}`);
      found[symbol.id] = file;
    }
  }
  return found;
}

function checkStates(name, category) {
  const states = Object.keys(category.states);
  if (category.kind === 'static') {
    if (states.join() !== 'static' || category.states.static.frames.length !== 1) fail(`category ${name}: a static category has one state "static" with one frame`);
    return;
  }
  const expected = REDUCED_STATES[name] ?? Object.keys(STATE_RULES);
  if (states.slice().sort().join() !== expected.slice().sort().join()) fail(`category ${name}: states must be ${expected.join(', ')}, got ${states.join(', ')}`);
  for (const [state, { frames, frameMs }] of Object.entries(category.states)) {
    const [min, max] = STATE_RULES[state] ?? [1, 4];
    if (frames.length < min || frames.length > max) fail(`category ${name} ${state}: ${frames.length} frames, need ${min}-${max}`);
    if (!(frameMs > 0)) fail(`category ${name} ${state}: frameMs must be above 0`);
  }
  checkAnchors(name, category);
}

/** Anchors: one set per frame, inside the box, and an `impact` on every acted-upon frame (the effect icon goes there). */
function checkAnchors(name, category) {
  for (const [state, { frames, anchors }] of Object.entries(category.states)) {
    if (anchors && anchors.length !== frames.length) fail(`category ${name} ${state}: ${anchors.length} anchor sets for ${frames.length} frames`);
    frames.forEach((id, i) => {
      const set = anchors?.[i] ?? {};
      for (const [label, [x, y]] of Object.entries(set)) {
        if (Math.abs(x) > 16 || Math.abs(y) > 16) fail(`${id}: anchor ${label} (${x}, ${y}) is outside the box`);
      }
      if (state === 'acted-upon' && !set.impact) fail(`${id}: an acted-upon frame needs an impact anchor (where the effect icon is drawn)`);
    });
  }
}

function checkSymbolsMatchManifest(found) {
  const named = new Set();
  for (const [name, category] of Object.entries(manifest.categories)) {
    checkStates(name, category);
    for (const [state, { frames }] of Object.entries(category.states)) {
      frames.forEach((id, i) => {
        named.add(id);
        if (id !== `${name}.${state}.${i}`) fail(`category ${name}: frame ${id} should be ${name}.${state}.${i}`);
        if (found[id] !== category.file) fail(`symbol ${id} is not in ${category.file}`);
      });
    }
  }
  for (const [name, overlay] of Object.entries(manifest.overlays)) {
    const id = typeof overlay === 'string' ? overlay : overlay.symbol;
    named.add(id);
    if (id !== `overlay.${name}`) fail(`overlay ${name}: id should be overlay.${name}`);
    if (found[id] !== 'overlays.svg') fail(`overlay symbol ${id} is not in overlays.svg`);
  }
  for (const [kind, { symbol }] of Object.entries(manifest.effects.kinds)) {
    named.add(symbol);
    if (symbol !== `effect.${kind}`) fail(`effect ${kind}: symbol should be effect.${kind}`);
    if (found[symbol] !== 'effects.svg') fail(`effect symbol ${symbol} is not in effects.svg`);
  }
  const kinds = manifest.effects.kinds;
  if (!kinds[manifest.effects.default]) fail(`effects: default ${manifest.effects.default} is not a kind`);
  for (const [type, kind] of Object.entries(manifest.effects.types)) {
    if (!kinds[kind]) fail(`effects: type ${type} maps to unknown kind ${kind}`);
  }
  for (const [name, pattern] of Object.entries(manifest.patterns)) {
    const id = `pattern.${name}`;
    named.add(id);
    if (pattern.file !== `patterns/${name}.svg`) fail(`pattern ${name}: file should be patterns/${name}.svg`);
    if (found[id] !== pattern.file) fail(`pattern symbol ${id} is not in ${pattern.file}`);
  }
  for (const [material, pattern] of Object.entries(manifest.materials)) {
    if (!manifest.patterns[pattern]) fail(`material ${material}: unknown pattern ${pattern}`);
  }
  for (const id of Object.keys(found)) {
    if (!named.has(id)) fail(`stray symbol ${id} (not named in sprites.json)`);
  }
}

function checkVariants() {
  for (const [proto, variant] of Object.entries(manifest.variants)) {
    if (!manifest.categories[variant.category]) fail(`variant ${proto}: unknown category ${variant.category}`);
    for (const o of variant.overlays) {
      const overlay = manifest.overlays[o];
      if (!overlay) fail(`variant ${proto}: unknown overlay ${o}`);
      else if (typeof overlay !== 'string') checkOverlayAnchor(proto, variant, o, overlay.anchor);
    }
  }
}

/** An anchored overlay is drawn only on frames that have its anchor: its variant's category needs it on every frame. */
function checkOverlayAnchor(proto, variant, name, anchor) {
  const category = manifest.categories[variant.category];
  if (!category) return;
  for (const [state, { frames, anchors }] of Object.entries(category.states)) {
    frames.forEach((id, i) => {
      if (!anchors?.[i]?.[anchor]) fail(`variant ${proto}: overlay ${name} hangs on ${anchor}, which frame ${id} lacks`);
    });
  }
}

function prototypesOnDisk() {
  const names = new Set();
  for (const dir of PROTOTYPE_DIRS) {
    for (const file of readdirSync(join(repo, dir)).filter((f) => f.endsWith('.json'))) {
      const json = JSON.parse(readFileSync(join(repo, dir, file), 'utf8'));
      const proto = json.prototype;
      // A fixed entity with no radius is a polygon (a wall), drawn with its material's pattern, not a
      // sprite (docs/resources.md, "Walls"; #297); its material comes from the level, not the prototype.
      // A placeholder has no radius either, and is excepted by NO_SPRITE.
      if (json.radius === undefined && json.entityKind === 'fixed' && proto !== 'placeholder') continue;
      if (json.material !== undefined && !manifest.materials[json.material]) {
        fail(`${dir}/${file}: material "${json.material}" has no pattern in sprites.json`);
      }
      if (proto) names.add(proto);
      else fail(`${dir}/${file}: no "prototype" key`);
    }
  }
  return names;
}

function checkCoverage() {
  for (const proto of prototypesOnDisk()) {
    if (!NO_SPRITE.has(proto) && !manifest.variants[proto]) {
      fail(`prototype "${proto}" has no variant in sprites.json (add one, or draw a new category)`);
    }
  }
}

/**
 * Every pattern a material names exists, and every material has a fallback in `materials` that is
 * one of its own patterns: a game with no `materialPatterns` draws it so.
 */
function checkMaterials() {
  const catalogue = JSON.parse(readFileSync(join(repo, MATERIALS), 'utf8')).materials;
  for (const material of catalogue) {
    for (const p of material.patterns) {
      if (!manifest.patterns[p]) fail(`${MATERIALS}: ${material.name} names pattern "${p}", which sprites.json lacks`);
    }
    const fallback = manifest.materials[material.name];
    if (!fallback) fail(`material ${material.name} has no fallback pattern in sprites.json materials`);
    else if (!material.patterns.includes(fallback)) {
      fail(`material ${material.name}: fallback ${fallback} is not one of its patterns ${material.patterns.join(', ')}`);
    }
  }
  const known = new Set(catalogue.map((m) => m.name));
  for (const name of Object.keys(manifest.materials)) {
    if (!known.has(name)) fail(`sprites.json materials: ${name} is not in ${MATERIALS}`);
  }
}

checkSchema();
checkSymbolsMatchManifest(parseAll());
checkVariants();
checkCoverage();
checkMaterials();
checkSamples();

if (problems.length) {
  console.error(problems.map((p) => `FAIL ${p}`).join('\n'));
  console.error(`${problems.length} problem(s)`);
  process.exit(1);
}
const frames = Object.values(manifest.categories).flatMap((c) => Object.values(c.states)).reduce((n, s) => n + s.frames.length, 0);
console.log(`sprites ok: ${Object.keys(manifest.categories).length} categories, ${frames} frames, ${Object.keys(manifest.overlays).length} overlays, ${Object.keys(manifest.variants).length} variants, ${Object.keys(manifest.patterns).length} patterns`);
