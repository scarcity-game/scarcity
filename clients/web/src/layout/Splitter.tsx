import { useRef, type KeyboardEvent, type PointerEvent } from 'react';
import { MIN_SIZE } from './layoutStore.ts';

const KEY_STEP_PX = 16;

export interface SplitterProps {
  /** `vertical` is a vertical bar between left and right; `horizontal` a bar between top and bottom. */
  orientation: 'vertical' | 'horizontal';
  label: string;
  /** Size in pixels of the region after the bar (right or bottom); dragging toward the map grows it. */
  value: number;
  /** The largest the region may get, in pixels. */
  max: number;
  onChange: (value: number) => void;
}

interface Start {
  pointerId: number;
  at: number;
  value: number;
}

function clampSize(value: number, max: number): number {
  return Math.round(Math.max(MIN_SIZE, Math.min(max, value)));
}

/** Pixels the region grows by for a key: left or up grows it, right or down shrinks it. */
function keyGrowth(key: string, orientation: SplitterProps['orientation']): number {
  const grow = orientation === 'vertical' ? 'ArrowLeft' : 'ArrowUp';
  const shrink = orientation === 'vertical' ? 'ArrowRight' : 'ArrowDown';
  if (key === grow) return KEY_STEP_PX;
  return key === shrink ? -KEY_STEP_PX : 0;
}

/** Pointer handlers that turn a drag along the bar's axis into a new size. */
function useSplitterDrag({ orientation, value, max, onChange }: SplitterProps) {
  const start = useRef<Start | null>(null);
  const coordinate = (e: PointerEvent) => (orientation === 'vertical' ? e.clientX : e.clientY);
  const end = () => (start.current = null);
  return {
    onPointerDown: (e: PointerEvent<HTMLDivElement>) => {
      start.current = { pointerId: e.pointerId, at: coordinate(e), value };
      e.currentTarget.setPointerCapture?.(e.pointerId);
    },
    onPointerMove: (e: PointerEvent<HTMLDivElement>) => {
      const s = start.current;
      if (s?.pointerId === e.pointerId) onChange(clampSize(s.value - (coordinate(e) - s.at), max));
    },
    onPointerUp: end,
    onPointerCancel: end,
  };
}

/** A draggable, keyboard-operable separator (WAI-ARIA window splitter). */
export function Splitter(props: SplitterProps) {
  const { orientation, label, value, max, onChange } = props;
  const drag = useSplitterDrag(props);
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const growth = keyGrowth(e.key, orientation);
    if (growth === 0) return;
    e.preventDefault();
    onChange(clampSize(value + growth, max));
  };
  return (
    <div
      className="splitter"
      data-orientation={orientation}
      role="separator"
      aria-orientation={orientation}
      aria-label={label}
      aria-valuemin={MIN_SIZE}
      aria-valuemax={max}
      aria-valuenow={Math.round(value)}
      tabIndex={0}
      onKeyDown={onKeyDown}
      {...drag}
    />
  );
}
