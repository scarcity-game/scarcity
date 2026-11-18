/** Which trees of the log are rendered: a window of them, the rest are spacers. */

export const WINDOW_TREES = 60;
export const OVERSCAN_TREES = 15;

export interface TreeWindow {
  start: number;
  end: number;
}

export interface WindowInput {
  treeCount: number;
  scrollTop: number;
  /** The average rendered height of a tree, px. */
  avgPx: number;
  /** The log is scrolled to the bottom and follows new entries. */
  stick: boolean;
  /** The entry the replay is at, null when live. */
  currentSeq: number | null;
  /** The index of the tree holding `currentSeq`, if any. */
  currentTree: number | undefined;
}

/** The first tree the scroll position puts in view, less the overscan (not clamped). */
function scrolledStart(scrollTop: number, avgPx: number): number {
  return Math.floor(scrollTop / avgPx) - OVERSCAN_TREES;
}

/** Whether tree `tree` is outside the window the scroll position implies. */
export function isOutsideWindow(
  tree: number,
  scrollTop: number,
  avgPx: number,
  treeCount: number,
): boolean {
  const start = scrolledStart(scrollTop, avgPx);
  const end = Math.min(treeCount, start + WINDOW_TREES);
  return tree < start || tree >= end;
}

// Widens the window so that tree `tree` is in it.
function including(window: TreeWindow, tree: number, treeCount: number): TreeWindow {
  if (tree < window.start) {
    const start = Math.max(0, tree - OVERSCAN_TREES);
    return { start, end: Math.min(treeCount, start + WINDOW_TREES) };
  }
  if (tree >= window.end) {
    const end = Math.min(treeCount, tree + 1 + OVERSCAN_TREES);
    return { start: Math.max(0, end - WINDOW_TREES), end };
  }
  return window;
}

export function visibleWindow(input: WindowInput): TreeWindow {
  const { treeCount, scrollTop, avgPx, stick, currentSeq, currentTree } = input;
  const maxStart = Math.max(0, treeCount - WINDOW_TREES);
  const raw = stick && currentSeq === null ? maxStart : scrolledStart(scrollTop, avgPx);
  const start = Math.max(0, Math.min(raw, maxStart));
  const window = { start, end: Math.min(treeCount, start + WINDOW_TREES) };
  if (currentSeq === null || currentTree === undefined) return window;
  return including(window, currentTree, treeCount);
}
