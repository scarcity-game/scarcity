/** A rectangle in SVG user units (world units, y already flipped). */
export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export const MIN_ZOOM = 0.5; // times the fitted box
export const MAX_ZOOM = 40;

/** What the SVG element occupies on screen, in pixels. */
export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

export function boxString(box: Box): string {
  return `${box.x} ${box.y} ${box.w} ${box.h}`;
}

/** Pixels per user unit under `preserveAspectRatio="meet"`; 1 when the element has no size (tests). */
export function pixelsPerUnit(rect: Rect, box: Box): number {
  return Math.min(rect.width / box.w, rect.height / box.h) || 1;
}

/** The point of user space under a client position. */
export function unitAt(rect: Rect, box: Box, client: Point): Point {
  const ppu = pixelsPerUnit(rect, box);
  const offsetX = (rect.width - box.w * ppu) / 2;
  const offsetY = (rect.height - box.h * ppu) / 2;
  return {
    x: box.x + (client.x - rect.left - offsetX) / ppu,
    y: box.y + (client.y - rect.top - offsetY) / ppu,
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function centreOf(box: Box): Point {
  return { x: box.x + box.w / 2, y: box.y + box.h / 2 };
}

/** The box with its centre moved to `point`. */
export function centreBox(box: Box, point: Point): Box {
  return { ...box, x: point.x - box.w / 2, y: point.y - box.h / 2 };
}

/** How much the view magnifies the room when following starts from the fitted view. */
export const FOLLOW_ZOOM = 4;

/** Moves the view by user units; the box centre stays inside the fitted box. */
export function panBox(box: Box, base: Box, dx: number, dy: number): Box {
  const centre = centreOf(box);
  const cx = clamp(centre.x + dx, base.x, base.x + base.w);
  const cy = clamp(centre.y + dy, base.y, base.y + base.h);
  return { ...box, x: cx - box.w / 2, y: cy - box.h / 2 };
}

/** Zooms by `factor` (above 1 magnifies), keeping the user-space point `focus` where it is on screen. */
export function zoomBox(box: Box, base: Box, factor: number, focus: Point): Box {
  const w = clamp(box.w / factor, base.w / MAX_ZOOM, base.w / MIN_ZOOM);
  const ratio = w / box.w;
  return {
    x: focus.x - (focus.x - box.x) * ratio,
    y: focus.y - (focus.y - box.y) * ratio,
    w,
    h: box.h * ratio,
  };
}
