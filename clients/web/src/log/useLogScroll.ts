import { useLayoutEffect, useState, type RefObject, type UIEvent } from 'react';
import type { LogIndex } from './logIndex.ts';
import type { LogEntry } from './types.ts';
import { isOutsideWindow } from './logWindow.ts';

const STICK_SLOP_PX = 8;
const EST_ROW_PX = 24;

export interface LogScroll {
  scrollRef: RefObject<HTMLDivElement>;
  /** The average rendered height of a row, px. */
  avgRef: { current: number };
  /** The log is scrolled to the bottom and follows new entries. */
  stickRef: { current: boolean };
  scrollTop: number;
  onScroll: (event: UIEvent<HTMLDivElement>) => void;
}

interface ScrollRefs {
  scrollRef: RefObject<HTMLDivElement>;
  avgRef: { current: number };
  stickRef: { current: boolean };
  /** The `scrollTop` state as of the last render. */
  scrollTopRef: { current: number };
  /** Set when the marked entry should be nudged into view after the next render. */
  pendingNudgeRef: { current: boolean };
  setScrollTop: (value: number) => void;
}

// Brings the marked (current) entry into view, if it is not.
function nudgeMarkedIntoView(el: HTMLElement): void {
  const marked = el.querySelector('[aria-current="true"]');
  if (!marked) return;
  const rect = marked.getBoundingClientRect();
  const top = rect.top - el.getBoundingClientRect().top + el.scrollTop;
  if (top < el.scrollTop) {
    el.scrollTop = top;
  } else if (top + rect.height > el.scrollTop + el.clientHeight) {
    el.scrollTop = top + rect.height - el.clientHeight;
  }
}

// Moves the view so the current entry's row `row` is shown, if it is not.
function revealRow(el: HTMLElement, row: number, refs: ScrollRefs, rowCount: number): void {
  // Only recentre if the current entry is not within the visible window
  if (isOutsideWindow(row, refs.scrollTopRef.current, refs.avgRef.current, rowCount)) {
    el.scrollTop = Math.max(0, row * refs.avgRef.current - el.clientHeight / 2);
    refs.setScrollTop(el.scrollTop);
  }
  refs.pendingNudgeRef.current = true;
}

interface Followed {
  index: LogIndex;
  entries: readonly LogEntry[];
  currentSeq: number | null;
}

// Keeps the view where it belongs when the log changes: at the bottom while
// live, on the current entry in a replay.
function useFollow(refs: ScrollRefs, { index, entries, currentSeq }: Followed): void {
  useLayoutEffect(() => {
    const el = refs.scrollRef.current;
    if (!el) return;
    if (currentSeq === null) {
      // In live mode, maintain stick-to-bottom behavior
      if (refs.stickRef.current) el.scrollTop = el.scrollHeight;
      return;
    }
    // In replay mode, scroll to the current entry
    const row = index.rowOfSeq.get(currentSeq);
    if (row !== undefined) revealRow(el, row, refs, index.rows.length);
  }, [currentSeq, entries, index, refs]);
}

// After render, ensures the marked element is properly scrolled into view.
function useNudge(refs: ScrollRefs): void {
  const syncScrollTop = (value: number) => refs.setScrollTop(value);
  useLayoutEffect(() => {
    const el = refs.scrollRef.current;
    if (!el || !refs.pendingNudgeRef.current) return;
    nudgeMarkedIntoView(el);
    if (el.scrollTop !== refs.scrollTopRef.current) syncScrollTop(el.scrollTop);
    refs.pendingNudgeRef.current = false;
  });
}

/**
 * The log's scroll state: stick-to-bottom while live, recentring on the
 * current entry in a replay, and the scroll position the window is computed from.
 */
export function useLogScroll(
  index: LogIndex,
  entries: readonly LogEntry[],
  currentSeq: number | null,
): LogScroll {
  const [scrollTop, setScrollTop] = useState(0);
  // One stable object of refs (and the setter), so effects can depend on it.
  const [refs] = useState<ScrollRefs>(() => ({
    scrollRef: { current: null },
    avgRef: { current: EST_ROW_PX },
    stickRef: { current: true },
    scrollTopRef: { current: 0 },
    pendingNudgeRef: { current: false },
    setScrollTop,
  }));
  refs.scrollTopRef.current = scrollTop;
  useFollow(refs, { index, entries, currentSeq });
  useNudge(refs);

  const onScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    refs.stickRef.current = el.scrollTop + el.clientHeight >= el.scrollHeight - STICK_SLOP_PX;
    setScrollTop(el.scrollTop);
  };
  return {
    scrollRef: refs.scrollRef,
    avgRef: refs.avgRef,
    stickRef: refs.stickRef,
    scrollTop,
    onScroll,
  };
}

/** After each render, updates the average row height from the `count` rows rendered. */
export function useAverageHeight(scroll: LogScroll, count: number): void {
  const { scrollRef, avgRef } = scroll;
  useLayoutEffect(() => {
    const ul = scrollRef.current?.querySelector('.log-rows');
    if (!ul || ul.children.length === 0) return;
    const height = ul.scrollHeight;
    if (count > 0 && height > 0) avgRef.current = height / count;
  });
}
