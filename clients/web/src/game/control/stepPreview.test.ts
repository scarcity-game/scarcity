import { describe, expect, it } from 'vitest';
import { arrowTarget, isArrowKey, stepPreview } from './stepPreview.ts';
import { previewText } from './MoveAction.tsx';

const from = { x: 2, y: 3 };

describe('stepPreview', () => {
  it('steps to a target within the reach, taking distance / speed', () => {
    expect(stepPreview(from, { x: 5, y: 7 }, 10, 2)).toEqual({
      to: { x: 5, y: 7 },
      distance: 5,
      duration: 2.5,
      pulledBack: false,
    });
  });

  it('pulls a target beyond the reach back to its edge on the same line', () => {
    const p = stepPreview(from, { x: 8, y: 11 }, 5, 1)!;
    expect(p.pulledBack).toBe(true);
    expect(p.distance).toBe(5);
    expect(p.to.x).toBeCloseTo(5);
    expect(p.to.y).toBeCloseTo(7);
    expect(p.duration).toBe(5);
  });

  it('is null for a zero-length step', () => {
    expect(stepPreview(from, from, 5, 1)).toBeNull();
  });

  it('pulls nothing back without a reach, and has no duration without a speed', () => {
    const p = stepPreview(from, { x: 102, y: 3 }, undefined, undefined)!;
    expect(p).toMatchObject({ to: { x: 102, y: 3 }, distance: 100, pulledBack: false });
    expect(p.duration).toBeNaN();
  });
});

describe('arrowTarget', () => {
  it('is 1 m in the arrow direction, up being north (y up)', () => {
    expect(arrowTarget('ArrowUp', from)).toEqual({ x: 2, y: 4 });
    expect(arrowTarget('ArrowDown', from)).toEqual({ x: 2, y: 2 });
    expect(arrowTarget('ArrowLeft', from)).toEqual({ x: 1, y: 3 });
    expect(arrowTarget('ArrowRight', from)).toEqual({ x: 3, y: 3 });
    expect(arrowTarget('w', from)).toBeNull();
    expect(isArrowKey('ArrowUp')).toBe(true);
    expect(isArrowKey('Enter')).toBe(false);
  });
});

describe('previewText', () => {
  it('tells the distance and time, and when the step is pulled back to the reach', () => {
    expect(previewText(stepPreview(from, { x: 5, y: 7 }, 10, 2)!)).toBe('5 m, 2.5 s');
    expect(previewText(stepPreview(from, { x: 8, y: 11 }, 5, 1)!)).toBe('5 m, 5 s (the reach)');
    expect(previewText(stepPreview(from, { x: 4.5, y: 4.5 }, 5, 1)!)).toBe('2.9 m, 2.9 s');
  });
});
