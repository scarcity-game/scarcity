// The SVG rules for sprites: allowed elements and attributes, colour only
// through paint slots, minimum stroke width, and a bounding box check.
const SHAPES = new Set(['path', 'circle', 'ellipse', 'rect', 'polygon', 'polyline', 'line']);
const CONTAINERS = new Set(['symbol', 'g']);
const COMMON = ['id', 'transform', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
  'fill-opacity', 'stroke-opacity', 'opacity', 'fill-rule'];
const GEOMETRY = {
  symbol: ['viewBox'],
  g: [],
  path: ['d'],
  circle: ['cx', 'cy', 'r'],
  ellipse: ['cx', 'cy', 'rx', 'ry'],
  rect: ['x', 'y', 'width', 'height'],
  polygon: ['points'],
  polyline: ['points'],
  line: ['x1', 'y1', 'x2', 'y2'],
};
// Which slots a symbol may use is the check's (its category's `slots`); here any slot name is a colour.
const COLOUR = /^(none|currentColor|var\(--sprite-[a-z][a-z-]*,\s*#[0-9a-fA-F]{3,8}\))$/;
const MIN_STROKE = 1.5;
const LIMIT = 16;
/** An affine matrix [a b c d e f]: x' = a x + c y + e, y' = b x + d y + f. */
const IDENTITY = [1, 0, 0, 1, 0, 0];

const nums = (text) => (text.match(/-?\d*\.?\d+(?:e-?\d+)?/gi) ?? []).map(Number);

/** The product m * n: n applied first, then m. */
function multiply(m, n) {
  return [
    m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
    m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
    m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5],
  ];
}

function parseTransform(text, problems) {
  let m = IDENTITY;
  for (const part of text.matchAll(/\s*(\w+)\(([^)]*)\)\s*/g)) {
    const a = nums(part[2]);
    if (part[1] === 'translate') {
      m = multiply(m, [1, 0, 0, 1, a[0], a[1] ?? 0]);
    } else if (part[1] === 'scale') {
      m = multiply(m, [a[0], 0, 0, a[1] ?? a[0], 0, 0]);
    } else if (part[1] === 'rotate') {
      const r = (a[0] * Math.PI) / 180;
      const [cx, cy] = [a[1] ?? 0, a[2] ?? 0];
      m = multiply(m, [1, 0, 0, 1, cx, cy]);
      m = multiply(m, [Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0]);
      m = multiply(m, [1, 0, 0, 1, -cx, -cy]);
    } else {
      problems.push(`transform ${part[1]}() is not allowed (translate, scale and rotate only)`);
    }
  }
  return m;
}

const corners = (x0, y0, x1, y1) => [[x0, y0], [x1, y0], [x1, y1], [x0, y1]];

/** Points that bound a shape, in its own coordinates (curves: control points). */
function shapePoints(tag, a, problems) {
  const f = (k) => Number(a[k] ?? 0);
  switch (tag) {
    // Four corners of the box, so a rotation still bounds it (for round shapes, a little generously).
    case 'circle': return corners(f('cx') - f('r'), f('cy') - f('r'), f('cx') + f('r'), f('cy') + f('r'));
    case 'ellipse': return corners(f('cx') - f('rx'), f('cy') - f('ry'), f('cx') + f('rx'), f('cy') + f('ry'));
    case 'rect': return corners(f('x'), f('y'), f('x') + f('width'), f('y') + f('height'));
    case 'line': return [[f('x1'), f('y1')], [f('x2'), f('y2')]];
    case 'polygon':
    case 'polyline': {
      const v = nums(a.points ?? '');
      return Array.from({ length: Math.floor(v.length / 2) }, (_, i) => [v[2 * i], v[2 * i + 1]]);
    }
    default: return pathPoints(a.d ?? '', problems);
  }
}

function pathPoints(d, problems) {
  const out = [];
  let cur = [0, 0];
  for (const seg of d.matchAll(/([A-Za-z])([^A-Za-z]*)/g)) {
    const cmd = seg[1];
    const v = nums(seg[2]);
    if (!'MLHVCQZ'.includes(cmd)) {
      problems.push(`path command ${cmd} is not allowed (absolute M L H V C Q Z only)`);
    } else if (cmd === 'H') {
      cur = [v[0], cur[1]];
      out.push(cur);
    } else if (cmd === 'V') {
      cur = [cur[0], v[0]];
      out.push(cur);
    } else {
      for (let i = 0; i + 1 < v.length; i += 2) out.push([v[i], v[i + 1]]);
      if (v.length >= 2) cur = [v[v.length - 2], v[v.length - 1]];
    }
  }
  return out;
}

function attributeProblems(tag, attrs, problems) {
  const allowed = new Set([...COMMON, ...GEOMETRY[tag]]);
  for (const [name, value] of Object.entries(attrs)) {
    if (!allowed.has(name)) problems.push(`<${tag}> attribute ${name} is not allowed`);
    else if ((name === 'fill' || name === 'stroke') && !COLOUR.test(value)) {
      problems.push(`<${tag}> ${name}="${value}": colour only through var(--sprite-<slot>, #fallback), none or currentColor`);
    }
  }
}

function visit(node, inherited, box, problems) {
  const tag = Object.keys(node).find((k) => k !== ':@');
  const attrs = node[':@'] ?? {};
  if (tag === '#text') {
    if (node['#text'].trim()) problems.push('stray text');
    return;
  }
  if (!SHAPES.has(tag) && !CONTAINERS.has(tag)) {
    problems.push(`element <${tag}> is not allowed`);
    return;
  }
  attributeProblems(tag, attrs, problems);
  const style = {
    fill: attrs.fill ?? inherited.fill,
    stroke: attrs.stroke ?? inherited.stroke,
    width: Number(attrs['stroke-width'] ?? inherited.width),
    m: attrs.transform ? multiply(inherited.m, parseTransform(attrs.transform, problems)) : inherited.m,
  };
  if (CONTAINERS.has(tag)) {
    for (const child of node[tag]) visit(child, style, box, problems);
    return;
  }
  shapeProblems(tag, attrs, style, box, problems);
}

function shapeProblems(tag, attrs, style, box, problems) {
  const stroked = style.stroke !== undefined && style.stroke !== 'none';
  if (tag !== 'line' && style.fill === undefined) problems.push(`<${tag}> has no fill (the default is black; use a paint slot or none)`);
  if (stroked && style.width < MIN_STROKE) problems.push(`<${tag}> stroke width ${style.width} is below ${MIN_STROKE}`);
  // A scaled stroke scales too: pad by the larger axis scale.
  const k = Math.max(Math.hypot(style.m[0], style.m[1]), Math.hypot(style.m[2], style.m[3]));
  const pad = stroked ? (style.width * k) / 2 : 0;
  for (const [x, y] of shapePoints(tag, attrs, problems)) {
    const [a, b, c, d, e, f] = style.m;
    const px = a * x + c * y + e;
    const py = b * x + d * y + f;
    const { min, max } = box.bounds;
    if (box.offenders.length < 3 && (px - pad < min || py - pad < min || px + pad > max || py + pad > max)) {
      const r = (v) => Number(v.toFixed(2));
      box.offenders.push(`<${tag}> point (${r(px)}, ${r(py)})${pad ? ` + stroke ${r(pad)}` : ''}`);
    }
    box.minX = Math.min(box.minX, px - pad);
    box.maxX = Math.max(box.maxX, px + pad);
    box.minY = Math.min(box.minY, py - pad);
    box.maxY = Math.max(box.maxY, py + pad);
  }
}

function walk(symbol, bounds) {
  const problems = [];
  const box = { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, bounds, offenders: [] };
  visit(symbol, { fill: undefined, stroke: undefined, width: 1, m: IDENTITY }, box, problems);
  return { box, problems };
}

/** How far one parsed `symbol` node's drawing reaches, strokes included: {minX, maxX, minY, maxY}. */
export function extentOf(symbol) {
  const { minX, maxX, minY, maxY } = walk(symbol, { min: -LIMIT, max: LIMIT }).box;
  return { minX, maxX, minY, maxY };
}

/**
 * All problems with one parsed `symbol` node (fast-xml-parser, preserveOrder). `bounds` is the box its
 * drawing must stay in: a sprite's -16..16, a pattern tile's 0..64.
 */
export function elementProblems(symbol, bounds = { min: -LIMIT, max: LIMIT }) {
  const { box, problems } = walk(symbol, bounds);
  const { min, max } = bounds;
  if (box.minX < min || box.minY < min || box.maxX > max || box.maxY > max) {
    const r = (v) => Number(v.toFixed(2));
    problems.push(`outside the viewBox: x ${r(box.minX)}..${r(box.maxX)}, y ${r(box.minY)}..${r(box.maxY)} (limit ${min}..${max}, strokes and curve control points included); first offenders: ${box.offenders.join(', ')}`);
  }
  if (box.minX === Infinity) problems.push('draws nothing');
  return [...new Set(problems)];
}
