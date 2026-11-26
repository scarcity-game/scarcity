import type { Resource } from '../resources/cache.ts';
import type { Box } from './viewport.ts';

export interface Point {
  x: number;
  y: number;
}

/** A rectangle in world units, y up (`docs/resources.md` "Shapes"). */
export interface WorldBounds {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

function isPoint(value: unknown): value is Point {
  const p = value as Partial<Point> | null | undefined;
  return !!p && Number.isFinite(p.x) && Number.isFinite(p.y);
}

/** The entity's `position` (a polygon entity's is its centroid), or null. */
export function positionOf(entity: Resource): Point | null {
  return isPoint(entity.position) ? { x: entity.position.x, y: entity.position.y } : null;
}

/** The area's cells by id (`docs/resources.md` "Cells"). */
export type Cells = ReadonlyMap<string, Resource>;

function pointsOf(polygon: unknown): Point[] | null {
  if (!Array.isArray(polygon) || polygon.length < 3 || !polygon.every(isPoint)) return null;
  return polygon.map(({ x, y }) => ({ x, y }));
}

/** The cell a wall names (`cell`), when it is loaded. */
export function cellOf(entity: Resource, cells?: Cells): Resource | undefined {
  return typeof entity.cell === 'string' ? cells?.get(entity.cell) : undefined;
}

/**
 * The entity's polygon vertices in absolute area coordinates; null when it has none (a circle).
 * A wall has none of its own: its shape is its cell's polygon (a wall whose cell is not loaded
 * has no shape yet).
 */
export function polygonOf(entity: Resource, cells?: Cells): Point[] | null {
  const cell = cellOf(entity, cells);
  return pointsOf(cell ? cell.polygon : entity.polygon);
}

function circleBounds(entity: Resource): WorldBounds | null {
  const pos = positionOf(entity);
  const r = typeof entity.radius === 'number' && entity.radius > 0 ? entity.radius : 0;
  return pos && { minX: pos.x - r, minY: pos.y - r, maxX: pos.x + r, maxY: pos.y + r };
}

function pointsBounds(points: Point[]): WorldBounds {
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  return {
    minX: Math.min(...xs),
    minY: Math.min(...ys),
    maxX: Math.max(...xs),
    maxY: Math.max(...ys),
  };
}

/** One shape's bounding rectangle: a polygon's vertices, else a circle's `position` ± `radius`. */
export function boundsOfEntity(entity: Resource, cells?: Cells): WorldBounds | null {
  const polygon = polygonOf(entity, cells);
  return polygon ? pointsBounds(polygon) : circleBounds(entity);
}

function union(a: WorldBounds | null, b: WorldBounds | null): WorldBounds | null {
  if (!a || !b) return a ?? b;
  return {
    minX: Math.min(a.minX, b.minX),
    minY: Math.min(a.minY, b.minY),
    maxX: Math.max(a.maxX, b.maxX),
    maxY: Math.max(a.maxY, b.maxY),
  };
}

/** The bounding rectangle of the loaded shapes; the walls are the area's edge. */
export function boundsOfShapes(entities: readonly Resource[], cells?: Cells): WorldBounds | null {
  return entities.map((entity) => boundsOfEntity(entity, cells)).reduce(union, null);
}

/** The bounds as an SVG box (y flipped), grown by `margin` on every side. */
export function svgBoxOf(bounds: WorldBounds, margin: number): Box {
  return {
    x: bounds.minX - margin,
    y: -bounds.maxY - margin,
    w: bounds.maxX - bounds.minX + 2 * margin,
    h: bounds.maxY - bounds.minY + 2 * margin,
  };
}

/** World to SVG: y up becomes y down. */
export function toSvg(pos: Point): Point {
  return { x: pos.x, y: -pos.y };
}

/** A bounding box's size, in metres. */
export function sizeOf(bounds: WorldBounds): { width: number; height: number } {
  return { width: bounds.maxX - bounds.minX, height: bounds.maxY - bounds.minY };
}
