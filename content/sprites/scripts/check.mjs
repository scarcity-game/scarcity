// The module's test: manifest, sheets and prototype coverage. Exits 1 on any problem.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv from 'ajv';
import { XMLParser } from 'fast-xml-parser';
import { loadManifest, loadSheets, readJson, root } from './lib.mjs';
import { elementProblems } from './svg-rules.mjs';

const repo = join(root, '..', '..');
const PROTOTYPE_DIRS = [
  'libs/world-model/src/main/resources/prototypes',
  'libs/world-seed/src/main/resources/seed',
];
const NO_SPRITE = new Set(['placeholder']);
const STATE_RULES = {
  idle: [2, 4], moving: [4, 4], acting: [2, 4], 'acted-upon': [2, 4],
};
const REDUCED_STATES = { stone: ['idle', 'moving'] };
const MAX_SHEET_BYTES = 12 * 1024;

const problems = [];
const fail = (message) => problems.push(message);

const manifest = loadManifest();
const sheets = loadSheets(manifest);

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
  if (Buffer.byteLength(text) >= MAX_SHEET_BYTES) fail(`${file}: ${Buffer.byteLength(text)} bytes, the limit is under ${MAX_SHEET_BYTES}`);
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
  if (attrs.viewBox !== '-16 -16 32 32') fail(`${file}: <svg> viewBox must be "-16 -16 32 32"`);
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
  if (node[':@'].viewBox !== '-16 -16 32 32') fail(`${file} ${id}: viewBox must be "-16 -16 32 32"`);
  for (const p of elementProblems(node)) fail(`${file} ${id}: ${p}`);
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
  for (const [name, id] of Object.entries(manifest.overlays)) {
    named.add(id);
    if (id !== `overlay.${name}`) fail(`overlay ${name}: id should be overlay.${name}`);
    if (found[id] !== 'overlays.svg') fail(`overlay symbol ${id} is not in overlays.svg`);
  }
  for (const id of Object.keys(found)) {
    if (!named.has(id)) fail(`stray symbol ${id} (not named in sprites.json)`);
  }
}

function checkVariants() {
  for (const [proto, variant] of Object.entries(manifest.variants)) {
    if (!manifest.categories[variant.category]) fail(`variant ${proto}: unknown category ${variant.category}`);
    for (const o of variant.overlays) {
      if (!manifest.overlays[o]) fail(`variant ${proto}: unknown overlay ${o}`);
    }
  }
}

function prototypesOnDisk() {
  const names = new Set();
  for (const dir of PROTOTYPE_DIRS) {
    for (const file of readdirSync(join(repo, dir)).filter((f) => f.endsWith('.json'))) {
      const json = JSON.parse(readFileSync(join(repo, dir, file), 'utf8'));
      const proto = json.prototype;
      // An entity with no radius is a polygon (a wall), drawn with its material's pattern, not a sprite
      // (docs/resources.md, "Walls"; #297). A placeholder has no radius either, and is excepted by NO_SPRITE.
      if (json.radius === undefined && json.material !== undefined) continue;
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

checkSchema();
checkSymbolsMatchManifest(parseAll());
checkVariants();
checkCoverage();

if (problems.length) {
  console.error(problems.map((p) => `FAIL ${p}`).join('\n'));
  console.error(`${problems.length} problem(s)`);
  process.exit(1);
}
const frames = Object.values(manifest.categories).flatMap((c) => Object.values(c.states)).reduce((n, s) => n + s.frames.length, 0);
console.log(`sprites ok: ${Object.keys(manifest.categories).length} categories, ${frames} frames, ${Object.keys(manifest.overlays).length} overlays, ${Object.keys(manifest.variants).length} variants`);
