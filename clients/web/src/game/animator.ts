import type { LogEntry } from '../log/types.ts';
import type { Resource } from '../resources/cache.ts';
import {
  animationSpeed,
  applyAdvance,
  beatMs,
  beatProgress,
  planBeat,
  resourcesAt,
  splitAdvances,
  type Advance,
  type BeatPlan,
} from './animation.ts';

type Resources = ReadonlyMap<string, Resource>;

/** What to draw now: the resources (animated while busy) and the entities whose action is playing. */
export interface Frame {
  resources: Resources;
  /** The entities an effect of the playing beat has as its target. */
  actedUpon: ReadonlyMap<string, string>;
}

export interface AnimatorOptions {
  /** The animation speed factor, read at the start of every beat. */
  speed?: () => number;
  /** How long to show the old state while waiting for the log entries of a change (wall ms). */
  holdMs?: number;
  /** Whether a backlog snaps (see the class), read each time an advance is about to start. Default: never. */
  skipBacklog?: () => boolean;
}

const NOTHING: ReadonlyMap<string, string> = new Map();

interface Playing {
  advance: Advance;
  index: number;
  startedAt: number;
  duration: number;
  plan: BeatPlan;
}

/**
 * Plays the advances of one area, one beat per game time, in log order, on top
 * of the state the viewer was looking at (`base`). While idle the area is what
 * the store holds (the truth). Nothing here reads a clock: callers pass `now`.
 *
 * - `hold(truth, now)`: a change was announced; keep showing `truth` (the
 *   state before it) until the log entries that explain it arrive, or `holdMs`.
 * - `push(entries, truth, now)`: new log entries. Each `intent` starts an advance.
 * - `tick(now)` moves the beats on; `frame(truth, now)` is what to draw.
 *
 * An advance arriving mid-animation waits in the queue and plays in turn.
 * With `skipBacklog` on, when more than one waits the older ones snap to their
 * end state (skipped, not sped up).
 */
export class AreaAnimator {
  private base: Resources | null = null;
  private heldUntil: number | null = null;
  private playing: Playing | null = null;
  private queue: Advance[] = [];
  private skippedCount = 0;
  private readonly speed: () => number;
  private readonly holdMs: number;
  private readonly skipBacklog: () => boolean;
  private listeners = new Set<() => void>();

  constructor(options: AnimatorOptions = {}) {
    this.speed = options.speed ?? (() => animationSpeed);
    this.holdMs = options.holdMs ?? 3000;
    this.skipBacklog = options.skipBacklog ?? (() => false);
  }

  /** True while the animator shows something other than the truth (holding, playing or queued). */
  get busy(): boolean {
    return this.base !== null;
  }

  /** How many advances snapped to their end state instead of playing. */
  get skipped(): number {
    return this.skippedCount;
  }

  /** Advances waiting to play behind the current one (the ones that will snap are not counted). */
  get queued(): number {
    return this.skipBacklog() ? Math.min(this.queue.length, 1) : this.queue.length;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  hold(truth: Resources, now: number): void {
    if (this.base === null) {
      this.base = truth;
      this.emit();
    }
    if (this.playing === null && this.queue.length === 0) this.heldUntil = now + this.holdMs;
  }

  push(entries: readonly LogEntry[], truth: Resources, now: number): void {
    const advances = splitAdvances(entries);
    if (advances.length === 0) return;
    if (this.base === null) this.base = truth;
    this.heldUntil = null;
    this.queue.push(...advances);
    if (this.playing === null) this.startNext(now);
    this.emit();
  }

  tick(now: number): void {
    const before = this.busy;
    while (this.playing !== null && now - this.playing.startedAt >= this.playing.duration) {
      this.finishBeat();
    }
    if (
      this.playing === null &&
      this.base !== null &&
      this.heldUntil !== null &&
      now >= this.heldUntil
    ) {
      this.release();
    }
    if (before !== this.busy) this.emit();
  }

  frame(truth: Resources, now: number): Frame {
    const { base, playing } = this;
    if (base === null) return { resources: truth, actedUpon: NOTHING };
    if (playing === null) return { resources: base, actedUpon: NOTHING };
    const progress = beatProgress(playing.startedAt, now, playing.duration);
    return {
      resources: resourcesAt(playing.plan, progress),
      actedUpon: playing.plan.actedUpon,
    };
  }

  /** Forget everything (the area changed): back to showing the truth. */
  reset(): void {
    this.release();
    this.skippedCount = 0;
    this.emit();
  }

  private release(): void {
    this.base = null;
    this.heldUntil = null;
    this.playing = null;
    this.queue = [];
  }

  /** Starts the next queued advance that plays; snapped ones (a backlog, when skipping) are applied on the way. Idle when none. */
  private startNext(now: number): void {
    for (;;) {
      const next = this.queue.shift();
      if (next === undefined || this.base === null) {
        this.playing = null;
        this.base = null;
        return;
      }
      const snap = this.queue.length > 0 && this.skipBacklog();
      if (snap || next.beats.length === 0) {
        if (snap) this.skippedCount += 1;
        this.base = applyAdvance(this.base, next);
        continue;
      }
      this.playing = this.beginBeat(next, 0, this.base, now);
      return;
    }
  }

  private beginBeat(
    advance: Advance,
    index: number,
    before: Resources,
    startedAt: number,
  ): Playing {
    const beat = advance.beats[index]!;
    return {
      advance,
      index,
      startedAt,
      duration: beatMs(this.speed()),
      plan: planBeat(before, beat),
    };
  }

  /** The playing beat is over: on to the next beat, or to the next advance, or to the truth. */
  private finishBeat(): void {
    const playing = this.playing!;
    const endedAt = playing.startedAt + playing.duration;
    const next = playing.index + 1;
    if (next < playing.advance.beats.length) {
      this.playing = this.beginBeat(playing.advance, next, playing.plan.after, endedAt);
      return;
    }
    this.base = playing.plan.after;
    this.playing = null;
    this.startNext(endedAt);
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }
}
