import type { LogChange, LogEntry } from '../log/types.ts';
import type { Resource } from '../resources/cache.ts';
import { applyEntry } from '../replay/replay.ts';

/**
 * Presentation only. The animation is not tied to wall time or to the game-time
 * duration of an action: every beat (one game time of an advance) plays for
 * `BEAT_MS`, whatever its length in game time. The server's state is the truth;
 * the animation only shows what already happened.
 */
export const BEAT_MS = 250;

/** The one speed knob: 1 is the beat above, 2 halves it. A per-viewer preference can set it later (#155). */
export const animationSpeed = 1;

export function beatMs(speed: number = animationSpeed): number {
  return BEAT_MS / speed;
}

export interface Point {
  x: number;
  y: number;
}

/** The entries of one advance that play together: they happened at the same game time. */
export interface Beat {
  time: number;
  entries: readonly LogEntry[];
}

/** One advance: the entries from an `intent` up to the next one, and the beats they play as. */
export interface Advance {
  entries: readonly LogEntry[];
  beats: readonly Beat[];
}

/** The entries that show something happening. Intents and snapshots change nothing on the screen. */
const PLAYABLE = new Set([
  'action-start',
  'action-progress',
  'action-end',
  'action-interrupted',
  'action-cancelled',
  'effect',
]);

/** Same-time entries, in log order, are one beat. */
export function toBeats(entries: readonly LogEntry[]): Beat[] {
  const beats: { time: number; entries: LogEntry[] }[] = [];
  for (const entry of entries) {
    if (!PLAYABLE.has(entry.kind)) continue;
    const last = beats[beats.length - 1];
    if (last !== undefined && last.time === entry.time) last.entries.push(entry);
    else beats.push({ time: entry.time, entries: [entry] });
  }
  return beats;
}

/**
 * Splits log entries (in seq order) into advances: each `intent` starts one
 * (`docs/log.md`: every advance has exactly one). Snapshots are dropped; entries
 * before the first intent form an advance of their own.
 */
export function splitAdvances(entries: readonly LogEntry[]): Advance[] {
  const groups: LogEntry[][] = [];
  for (const entry of entries) {
    if (entry.kind === 'snapshot') continue;
    const last = groups[groups.length - 1];
    if (entry.kind === 'intent' || last === undefined) groups.push([entry]);
    else last.push(entry);
  }
  return groups.map((group) => ({ entries: group, beats: toBeats(group) }));
}

/** How far through a beat started at `startedAt`, 0 to 1. Wall time is passed in, never read. */
export function beatProgress(startedAt: number, now: number, duration: number): number {
  if (!(duration > 0)) return 1;
  return Math.min(1, Math.max(0, (now - startedAt) / duration));
}

export function lerpPoint(a: Point, b: Point, t: number): Point {
  return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
}

export function pointOf(value: unknown): Point | null {
  const p = value as Partial<Point> | null | undefined;
  return p && typeof p.x === 'number' && typeof p.y === 'number' ? { x: p.x, y: p.y } : null;
}

/** A segment one entity moves along during a beat. */
export interface Motion {
  from: Point;
  to: Point;
}

/**
 * Where a moving entity is headed in this entry: for the end of a `step` its
 * `params.to`, or `result.stoppedAt` when it was blocked (`result.reached` is
 * false); for the end of a `fly` its `params.to`. Anything else, and anything
 * malformed, falls back to the position in the entry's resulting state.
 */
export function motionTarget(entry: LogEntry, change: LogChange): Point | null {
  const resulting = pointOf(change.state?.position);
  if (entry.kind === 'action-interrupted' && entry.resource === change.id) {
    return resulting ?? interruptedAt(entry);
  }
  if (entry.kind !== 'action-end' || entry.resource !== change.id) return resulting;
  return actionTarget(entry) ?? resulting;
}

/** Where an interrupted step stopped: the mover stays there, it never goes on to `params.to`. */
function interruptedAt(entry: LogEntry): Point | null {
  return pointOf((entry.result as { stoppedAt?: unknown } | null | undefined)?.stoppedAt);
}

function actionTarget(entry: LogEntry): Point | null {
  const action = entry.actionInProgress;
  switch (action?.type) {
    case 'step':
      return stepTarget(entry, action.params?.to);
    case 'fly':
      return pointOf(action.params?.to);
    default:
      return null;
  }
}

function stepTarget(entry: LogEntry, to: unknown): Point | null {
  const result = entry.result as { reached?: unknown; stoppedAt?: unknown } | null | undefined;
  const stopped = result?.reached === false ? pointOf(result.stoppedAt) : null;
  return stopped ?? pointOf(to);
}

/**
 * What a beat does, from the state just before it: the state after it, the
 * segment each moved entity travels and the entities whose action is in it.
 */
export interface BeatPlan {
  before: ReadonlyMap<string, Resource>;
  after: ReadonlyMap<string, Resource>;
  motions: ReadonlyMap<string, Motion>;
  /** The targets of the beat's effects: what the entities in it are acted upon by. */
  actedUpon: ReadonlySet<string>;
}

function moveOf(
  entry: LogEntry,
  change: LogChange,
  running: ReadonlyMap<string, Resource>,
): Motion | null {
  const from = pointOf(running.get(change.id)?.position);
  const to = motionTarget(entry, change);
  if (from === null || to === null) return null;
  return from.x === to.x && from.y === to.y ? null : { from, to };
}

function recordMotions(
  entry: LogEntry,
  running: ReadonlyMap<string, Resource>,
  motions: Map<string, Motion>,
): void {
  for (const change of entry.changes) {
    if (change.op !== 'changed') continue;
    const move = moveOf(entry, change, running);
    if (move === null) continue;
    motions.set(change.id, { from: motions.get(change.id)?.from ?? move.from, to: move.to });
  }
}

export function planBeat(before: ReadonlyMap<string, Resource>, beat: Beat): BeatPlan {
  const running = new Map(before);
  const motions = new Map<string, Motion>();
  const actedUpon = new Set<string>();
  for (const entry of beat.entries) {
    if (entry.resource !== null && entry.kind === 'effect') actedUpon.add(entry.resource);
    for (const acted of entry.actedUpon ?? []) actedUpon.add(acted.resource);
    recordMotions(entry, running, motions);
    applyEntry(running, entry);
  }
  return { before, after: running, motions, actedUpon };
}

/**
 * The resources to draw `progress` (0 to 1) through a beat. The state before
 * it stays up until the end (so what is destroyed in the beat is still there),
 * what is created in it is there from the start, and what moves is on its
 * segment. At 1 it is the state after the beat.
 */
export function resourcesAt(plan: BeatPlan, progress: number): Map<string, Resource> {
  if (progress >= 1) return new Map(plan.after);
  const shown = new Map(plan.before);
  for (const [id, resource] of plan.after) {
    if (!shown.has(id)) shown.set(id, resource);
  }
  for (const [id, motion] of plan.motions) {
    const resource = shown.get(id);
    if (resource === undefined) continue;
    const position = lerpPoint(motion.from, motion.to, progress);
    shown.set(id, { ...resource, position });
  }
  return shown;
}

/** The state after applying every entry of an advance at once (a snap to its end state). */
export function applyAdvance(
  before: ReadonlyMap<string, Resource>,
  advance: Advance,
): Map<string, Resource> {
  const snapped = new Map(before);
  for (const entry of advance.entries) applyEntry(snapped, entry);
  return snapped;
}
