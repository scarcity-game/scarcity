/** Which rows of the log are rendered: a window of them, the rest are spacers. */

export const WINDOW_ROWS = 60;
export const OVERSCAN_ROWS = 15;

export interface RowWindow {
  start: number;
  end: number;
}

export interface WindowInput {
  rowCount: number;
  scrollTop: number;
  /** The average rendered height of a row, px. */
  avgPx: number;
  /** The log is scrolled to the bottom and follows new entries. */
  stick: boolean;
  /** The entry the replay is at, null when live. */
  currentSeq: number | null;
  /** The index of the row holding `currentSeq`, if any. */
  currentRow: number | undefined;
}

/** The first row the scroll position puts in view, less the overscan (not clamped). */
function scrolledStart(scrollTop: number, avgPx: number): number {
  return Math.floor(scrollTop / avgPx) - OVERSCAN_ROWS;
}

/** Whether row `row` is outside the window the scroll position implies. */
export function isOutsideWindow(
  row: number,
  scrollTop: number,
  avgPx: number,
  rowCount: number,
): boolean {
  const start = scrolledStart(scrollTop, avgPx);
  const end = Math.min(rowCount, start + WINDOW_ROWS);
  return row < start || row >= end;
}

// Widens the window so that row `row` is in it.
function including(window: RowWindow, row: number, rowCount: number): RowWindow {
  if (row < window.start) {
    const start = Math.max(0, row - OVERSCAN_ROWS);
    return { start, end: Math.min(rowCount, start + WINDOW_ROWS) };
  }
  if (row >= window.end) {
    const end = Math.min(rowCount, row + 1 + OVERSCAN_ROWS);
    return { start: Math.max(0, end - WINDOW_ROWS), end };
  }
  return window;
}

export function visibleWindow(input: WindowInput): RowWindow {
  const { rowCount, scrollTop, avgPx, stick, currentSeq, currentRow } = input;
  const maxStart = Math.max(0, rowCount - WINDOW_ROWS);
  const raw = stick && currentSeq === null ? maxStart : scrolledStart(scrollTop, avgPx);
  const start = Math.max(0, Math.min(raw, maxStart));
  const window = { start, end: Math.min(rowCount, start + WINDOW_ROWS) };
  if (currentSeq === null || currentRow === undefined) return window;
  return including(window, currentRow, rowCount);
}
