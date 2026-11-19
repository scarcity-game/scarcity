import { useState } from 'react';

/** Sizes of the resizable regions of the area screen, in pixels. */
export interface LayoutSizes {
  /** Width of the inspector (side) region. */
  side: number;
  /** Height of the log (footer) region. */
  log: number;
}

export const DEFAULT_SIZES: LayoutSizes = { side: 320, log: 220 };
export const MIN_SIZE = 120;
export const MAX_SIZE = 4000;
export const STORAGE_KEY = 'scarcity.areaLayout.v1';

function validSize(value: unknown): value is number {
  return (
    typeof value === 'number' && Number.isFinite(value) && value >= MIN_SIZE && value <= MAX_SIZE
  );
}

/** The saved sizes; the defaults when nothing usable is saved or storage is unavailable. */
export function loadSizes(): LayoutSizes {
  try {
    const saved: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null');
    const { side, log } = (saved ?? {}) as Partial<Record<keyof LayoutSizes, unknown>>;
    return {
      side: validSize(side) ? side : DEFAULT_SIZES.side,
      log: validSize(log) ? log : DEFAULT_SIZES.log,
    };
  } catch {
    return DEFAULT_SIZES;
  }
}

function saveSizes(sizes: LayoutSizes): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(sizes));
  } catch {
    // Storage is blocked or full: the layout just isn't remembered.
  }
}

function forgetSizes(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing saved that we could remove.
  }
}

/** The region sizes, remembered per browser. `reset` restores the defaults and forgets the saved ones. */
export function useLayoutSizes() {
  const [sizes, setSizes] = useState<LayoutSizes>(loadSizes);
  const update = (patch: Partial<LayoutSizes>) => {
    const next = { ...sizes, ...patch };
    setSizes(next);
    saveSizes(next);
  };
  const reset = () => {
    setSizes(DEFAULT_SIZES);
    forgetSizes();
  };
  return { sizes, update, reset };
}
