import { useSyncExternalStore } from 'react';
import type { Resource } from '../resources/cache.ts';
import type { Replay } from './replay.ts';

export type Speed = 1 | 4 | 16;
export const SPEEDS: readonly Speed[] = [1, 4, 16];

export interface PlayerState {
  index: number;
  seq: number;
  entryTime: number; /*replay.time: time of the last applied entry*/
  gameTime: number; /*the playback clock*/
  startTime: number; /*replay.snapshot.time*/
  endTime: number; /*replay.endTime*/
  playing: boolean;
  speed: Speed;
  atEnd: boolean; /*replay.index === replay.length-1 and gameTime >= endTime*/
  hasPrevRoot: boolean;
  hasNextRoot: boolean;
  resources: ReadonlyMap<string, Resource>
}

export class ReplayPlayer {
  private playing = false;
  private speed: Speed = 1;
  private gameTime: number;
  private lastNow: number;
  private timer: ReturnType<typeof setInterval> | null = null;
  private listeners = new Set<() => void>();
  private state: PlayerState;
  private readonly now: () => number;
  private readonly intervalMs: number;

  constructor(
    readonly replay: Replay,
    options: { now?: () => number; intervalMs?: number } = {},
  ) {
    this.now = options.now ?? (() => Date.now());
    this.intervalMs = options.intervalMs ?? 50;
    this.gameTime = replay.snapshot.time;
    this.lastNow = this.now();
    this.state = this.buildState();
  }

  private buildState(): PlayerState {
    const { replay } = this;
    return {
      index: replay.index,
      seq: replay.seq,
      entryTime: replay.time,
      gameTime: this.gameTime,
      startTime: replay.snapshot.time,
      endTime: replay.endTime,
      playing: this.playing,
      speed: this.speed,
      atEnd: replay.index === replay.length - 1 && this.gameTime >= replay.endTime,
      hasPrevRoot: replay.prevEffectRoot() !== null,
      hasNextRoot: replay.nextEffectRoot() !== null,
      resources: replay.resources,
    };
  }

  /** Publishes a new state object and tells the listeners. Always the last step of a change. */
  private commit(): void {
    this.state = this.buildState();
    for (const listener of [...this.listeners]) listener();
  }

  private stopTimer(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = (): PlayerState => this.state;

  play(): void {
    if (this.state.atEnd) this.moveTo(this.replay.snapshot.time);
    this.playing = true;
    this.lastNow = this.now();
    if (this.timer === null) this.timer = setInterval(() => this.advance(), this.intervalMs);
    this.commit();
  }

  pause(): void {
    this.playing = false;
    this.stopTimer();
    this.commit();
  }

  setSpeed(s: Speed): void {
    if (this.playing) this.step();
    this.speed = s;
    this.commit();
  }

  /** The scrubber: move the playback clock to `t` (clamped to the range). */
  seekTime(t: number): void {
    this.moveTo(t);
    this.lastNow = this.now();
    this.commit();
  }

  /** Jump to an entry by seq (a click in the log panel). */
  seekSeq(seq: number): void {
    this.replay.seekSeq(seq);
    this.gameTime = this.replay.time;
    this.lastNow = this.now();
    this.commit();
  }

  stepEntry(delta: 1 | -1): void {
    this.stopPlaying();
    this.replay.seekIndex(this.replay.index + delta);
    this.gameTime = this.replay.time;
    this.commit();
  }

  jumpRoot(direction: 'prev' | 'next'): void {
    const i = direction === 'prev' ? this.replay.prevEffectRoot() : this.replay.nextEffectRoot();
    if (i === null) return;
    this.stopPlaying();
    this.replay.seekIndex(i);
    this.gameTime = this.replay.time;
    this.commit();
  }

  dispose(): void {
    this.playing = false;
    this.stopTimer();
    this.listeners.clear();
  }

  /** Called by the interval; public so a test can drive it. */
  advance(): void {
    if (!this.playing) return;
    this.step();
    this.commit();
  }

  private stopPlaying(): void {
    this.playing = false;
    this.stopTimer();
  }

  private moveTo(t: number): void {
    const { replay } = this;
    this.gameTime = Math.max(replay.snapshot.time, Math.min(t, replay.endTime));
    replay.seekTime(this.gameTime);
  }

  /** Moves the clock on by the wall time since the last step; stops at the end of the range. */
  private step(): void {
    const nowMs = this.now();
    const target = this.gameTime + ((nowMs - this.lastNow) / 1000) * this.speed;
    this.lastNow = nowMs;
    this.moveTo(target);
    if (this.gameTime >= this.replay.endTime) this.stopPlaying();
  }
}

export function useReplayState(player: ReplayPlayer): PlayerState {
  return useSyncExternalStore(player.subscribe, player.getSnapshot);
}
