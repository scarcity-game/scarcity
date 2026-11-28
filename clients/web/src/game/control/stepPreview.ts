// The geometry of a `step` the player is choosing (`docs/action-types.md`, `step`), in world units
// (y up). The server decides; this only shows what would be asked for.
import type { Point } from '../../components/shapes.ts';

export interface StepPreview {
  /** Where the step goes: the target, or the reach's edge on the way to it. */
  to: Point;
  distance: number;
  /** Game seconds: distance / speed; NaN when the entity has no speed. */
  duration: number;
  /** The target was beyond the reach and `to` is pulled back to its edge. */
  pulledBack: boolean;
}

/** How far one arrow key steps, in metres. */
export const KEY_STEP = 1;

const ARROW_DIRECTIONS: Readonly<Record<string, Point>> = {
  ArrowLeft: { x: -1, y: 0 },
  ArrowRight: { x: 1, y: 0 },
  ArrowUp: { x: 0, y: 1 },
  ArrowDown: { x: 0, y: -1 },
};

export function isArrowKey(key: string): boolean {
  return key in ARROW_DIRECTIONS;
}

/** The point `KEY_STEP` from `from` in the arrow's direction (up is north); null for another key. */
export function arrowTarget(key: string, from: Point): Point | null {
  const d = ARROW_DIRECTIONS[key];
  return d ? { x: from.x + d.x * KEY_STEP, y: from.y + d.y * KEY_STEP } : null;
}

/**
 * The step from `from` toward `target`, pulled back along the same line to `reach` when it is
 * further (no reach: not pulled back). Null for a zero-length step, which the server refuses.
 */
export function stepPreview(
  from: Point,
  target: Point,
  reach: number | undefined,
  speed: number | undefined,
): StepPreview | null {
  const dx = target.x - from.x;
  const dy = target.y - from.y;
  const length = Math.hypot(dx, dy);
  if (!(length > 0)) return null;
  const pulledBack = reach !== undefined && length > reach;
  const distance = pulledBack ? reach : length;
  const k = distance / length;
  const to = { x: from.x + dx * k, y: from.y + dy * k };
  const duration = speed !== undefined && speed > 0 ? distance / speed : Number.NaN;
  return { to, distance, duration, pulledBack };
}
