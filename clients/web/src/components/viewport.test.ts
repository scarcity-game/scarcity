import { describe, expect, it } from 'vitest';
import {
  MAX_ZOOM,
  MIN_ZOOM,
  panBox,
  pixelsPerUnit,
  unitAt,
  zoomBox,
  type Box,
} from './viewport.ts';

const base: Box = { x: 0, y: 0, w: 100, h: 50 };

describe('viewport', () => {
  it('has pixels per unit from the tighter side (meet), and 1 for no size', () => {
    expect(pixelsPerUnit({ left: 0, top: 0, width: 200, height: 200 }, base)).toBe(2);
    expect(pixelsPerUnit({ left: 0, top: 0, width: 0, height: 0 }, base)).toBe(1);
  });

  it('finds the unit under a pixel, allowing for the centring offset', () => {
    const rect = { left: 10, top: 20, width: 200, height: 200 };
    // 2 px per unit; the 50-unit-high box is 100 px high, centred in 200: offset 50.
    expect(unitAt(rect, base, { x: 10, y: 70 })).toEqual({ x: 0, y: 0 });
    expect(unitAt(rect, base, { x: 110, y: 120 })).toEqual({ x: 50, y: 25 });
  });

  it('keeps the centre of a panned box inside the base', () => {
    const panned = panBox(base, base, 1000, -1000);
    expect(panned.x + panned.w / 2).toBe(100);
    expect(panned.y + panned.h / 2).toBe(0);
  });

  it('limits zoom', () => {
    const focus = { x: 50, y: 25 };
    expect(zoomBox(base, base, 1000, focus).w).toBeCloseTo(base.w / MAX_ZOOM);
    expect(zoomBox(base, base, 0.0001, focus).w).toBeCloseTo(base.w / MIN_ZOOM);
  });
});
