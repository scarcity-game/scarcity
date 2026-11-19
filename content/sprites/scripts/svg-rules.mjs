// The SVG rules for sprites: allowed elements and attributes, colour only
// through palette slots, minimum stroke width, and a bounding box check.
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
const COLOUR = /^(none|currentColor|var\(--sprite-(body|accent|detail|outline),\s*#[0-9a-fA-F]{3,8}\))$/;
const MIN_STROKE = 1.5;
const LIMIT = 16;
const IDENTITY = { sx: 1, sy: 1, tx: 0, ty: 0 };

const nums = (text) => (text.match(/-?\d*\.?\d+(?:e-?\d+)?/gi) ?? []).map(Number);

function parseTransform(text, problems) {
  let m = IDENTITY;
  for (const part of text.matchAll(/\s*(\w+)\(([^)]*)\)\s*/g)) {
    const a = nums(part[2]);
    if (part[1] === 'translate') {
      m = { ...m, tx: m.tx + m.sx * a[0], ty: m.ty + m.sy * (a[1] ?? 0) };
    } else if (part[1] === 'scale') {
      m = { ...m, sx: m.sx * a[0], sy: m.sy * (a[1] ?? a[0]) };
    } else {
      problems.push(`transform ${part[1]}() is not allowed (translate and scale only)`);
    }
  }
  return m;
}

/** Points that bound a shape, in its own coordinates (curves: control points). */
function shapePoints(tag, a, problems) {
  const f = (k) => Number(a[k] ?? 0);
  switch (tag) {
    case 'circle': return [[f('cx') - f('r'), f('cy') - f('r')], [f('cx') + f('r'), f('cy') + f('r')]];
    case 'ellipse': return [[f('cx') - f('rx'), f('cy') - f('ry')], [f('cx') + f('rx'), f('cy') + f('ry')]];
    case 'rect': return [[f('x'), f('y')], [f('x') + f('width'), f('y') + f('height')]];
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
    m: attrs.transform ? compose(inherited.m, parseTransform(attrs.transform, problems)) : inherited.m,
  };
  if (CONTAINERS.has(tag)) {
    for (const child of node[tag]) visit(child, style, box, problems);
    return;
  }
  shapeProblems(tag, attrs, style, box, problems);
}

function compose(parent, local) {
  return { sx: parent.sx * local.sx, sy: parent.sy * local.sy, tx: parent.tx + parent.sx * local.tx, ty: parent.ty + parent.sy * local.ty };
}

function shapeProblems(tag, attrs, style, box, problems) {
  const stroked = style.stroke !== undefined && style.stroke !== 'none';
  if (tag !== 'line' && style.fill === undefined) problems.push(`<${tag}> has no fill (the default is black; use a palette slot or none)`);
  if (stroked && style.width < MIN_STROKE) problems.push(`<${tag}> stroke width ${style.width} is below ${MIN_STROKE}`);
  const pad = stroked ? style.width / 2 : 0;
  for (const [x, y] of shapePoints(tag, attrs, problems)) {
    const px = x * style.m.sx + style.m.tx;
    const py = y * style.m.sy + style.m.ty;
    box.minX = Math.min(box.minX, px - pad);
    box.maxX = Math.max(box.maxX, px + pad);
    box.minY = Math.min(box.minY, py - pad);
    box.maxY = Math.max(box.maxY, py + pad);
  }
}

/** All problems with one parsed `symbol` node (fast-xml-parser, preserveOrder). */
export function elementProblems(symbol) {
  const problems = [];
  const box = { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity };
  visit(symbol, { fill: undefined, stroke: undefined, width: 1, m: IDENTITY }, box, problems);
  if (box.minX < -LIMIT || box.minY < -LIMIT || box.maxX > LIMIT || box.maxY > LIMIT) {
    const r = (v) => Number(v.toFixed(2));
    problems.push(`outside the viewBox: x ${r(box.minX)}..${r(box.maxX)}, y ${r(box.minY)}..${r(box.maxY)} (limit ${-LIMIT}..${LIMIT}, strokes included)`);
  }
  if (box.minX === Infinity) problems.push('draws nothing');
  return [...new Set(problems)];
}
