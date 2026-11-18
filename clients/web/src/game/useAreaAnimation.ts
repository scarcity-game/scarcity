import { useEffect, useRef, useState } from 'react';
import type { LogEntry } from '../log/types.ts';
import type { Resource } from '../resources/cache.ts';
import { AreaAnimator, type Frame } from './animator.ts';

type Resources = ReadonlyMap<string, Resource>;

/** How often the view redraws while an animation runs (wall ms). */
export const FRAME_MS = 33;

export interface AreaAnimation {
  /** A change batch with a `logSeq` is about to be applied to the store: keep the old state up until its log entries arrive. */
  hold(truth: Resources): void;
  /** The log now holds these entries (the whole log, newest last). Only entries after the log was first read play. */
  follow(entries: readonly LogEntry[], truth: Resources): void;
  /** What to draw: the truth, or the truth's older state with the running beat on it. */
  frame(truth: Resources): Frame;
}

/**
 * Wires an `AreaAnimator` to a view: redraws every `FRAME_MS` while it is busy,
 * and works out which log entries are new. The history read when the view
 * opens (and anything before the first change batch) is never animated.
 * `now` is the wall clock, injected so a test can drive it.
 */
export function useAreaAnimation(areaId: string, now: () => number = Date.now): AreaAnimation {
  const [animator] = useState(() => new AreaAnimator());
  const armedRef = useRef(false);
  const seenRef = useRef(0);
  const nowRef = useRef(now);
  nowRef.current = now;

  useEffect(() => {
    armedRef.current = false;
    seenRef.current = 0;
    animator.reset();
  }, [animator, areaId]);
  useFrameLoop(animator, nowRef);

  return {
    hold(truth) {
      if (!truth.has(areaId)) return;
      armedRef.current = true;
      animator.hold(truth, nowRef.current());
    },
    follow(entries, truth) {
      const fresh = freshEntries(entries, seenRef, armedRef);
      if (fresh.length > 0) animator.push(fresh, truth, nowRef.current());
    },
    frame: (truth) => animator.frame(truth, nowRef.current()),
  };
}

// Redraws every `FRAME_MS` while the animator is busy.
function useFrameLoop(animator: AreaAnimator, nowRef: { current: () => number }): void {
  const [busy, setBusy] = useState(false);
  const [, setFrameNo] = useState(0);

  useEffect(() => animator.subscribe(() => setBusy(animator.busy)), [animator]);

  useEffect(() => {
    if (!busy) return;
    const id = setInterval(() => {
      animator.tick(nowRef.current());
      setFrameNo((n) => n + 1);
    }, FRAME_MS);
    return () => clearInterval(id);
  }, [animator, busy, nowRef]);
}

// The entries to play: those after the last seen one, only once a change batch armed it.
function freshEntries(
  entries: readonly LogEntry[],
  seenRef: { current: number },
  armedRef: { current: boolean },
): readonly LogEntry[] {
  const newest = entries[entries.length - 1]?.seq ?? 0;
  if (newest < seenRef.current || entries.length === 0) {
    // The log was cleared or pruned: start over.
    seenRef.current = newest;
    armedRef.current = false;
    return [];
  }
  const fresh = entries.filter((e) => e.seq > seenRef.current);
  seenRef.current = newest;
  return armedRef.current ? fresh : [];
}
