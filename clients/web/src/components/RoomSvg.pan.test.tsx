import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const mouse: Resource = {
  id: 'entity:m',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: 'mouse',
  position: { x: 2, y: 2 },
};

/** A wall box round x 0..10, y 0..8: the shapes the view fits to. */
const wall: Resource = {
  id: 'entity:wall',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'fixed',
  prototype: 'stone-wall',
  polygon: [
    { x: 0, y: 0 },
    { x: 10, y: 0 },
    { x: 10, y: 8 },
    { x: 0, y: 8 },
  ],
  position: { x: 5, y: 4 },
};

function renderRoom() {
  const onSelect = vi.fn();
  render(<RoomSvg entities={[wall, mouse]} onSelect={onSelect} />);
  const svg = screen.getByRole('img', { name: 'Room' });
  // 1 pixel per world unit: the box is 11 x 9, so it fits an 11 x 9 element exactly.
  svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 11, height: 9 }) as DOMRect;
  return { svg, onSelect, mark: screen.getByRole('button', { name: 'mouse' }) };
}

function viewBoxOf(svg: Element): [number, number, number, number] {
  const [x = NaN, y = NaN, w = NaN, h = NaN] = (svg.getAttribute('viewBox') ?? '')
    .split(' ')
    .map(Number);
  return [x, y, w, h];
}

function drag(target: Element, from: [number, number], to: [number, number]) {
  fireEvent.pointerDown(target, { pointerId: 1, button: 0, clientX: from[0], clientY: from[1] });
  fireEvent.pointerMove(target, { pointerId: 1, clientX: to[0], clientY: to[1] });
  fireEvent.pointerUp(target, { pointerId: 1, clientX: to[0], clientY: to[1] });
}

describe('RoomSvg panning', () => {
  it('starts fitted, showing the whole room', () => {
    const { svg } = renderRoom();
    expect(viewBoxOf(svg)).toEqual([-0.5, -8.5, 11, 9]);
    expect(screen.getByRole('button', { name: 'Fit' })).toBeDisabled();
  });

  it('pans by dragging: the map follows the pointer', () => {
    const { svg } = renderRoom();
    drag(svg, [50, 50], [46, 53]);
    const [x, y, w, h] = viewBoxOf(svg);
    expect(x).toBeCloseTo(3.5);
    expect(y).toBeCloseTo(-11.5);
    expect([w, h]).toEqual([11, 9]);
  });

  it('pans by the arrow keys when the map has focus', () => {
    const { svg } = renderRoom();
    fireEvent.keyDown(svg, { key: 'ArrowRight' });
    expect(viewBoxOf(svg)[0]).toBeCloseTo(0.6);
    fireEvent.keyDown(svg, { key: 'ArrowDown' });
    expect(viewBoxOf(svg)[1]).toBeCloseTo(-7.6);
    fireEvent.keyDown(svg, { key: 'ArrowLeft' });
    fireEvent.keyDown(svg, { key: 'ArrowUp' });
    const [x, y] = viewBoxOf(svg);
    expect(x).toBeCloseTo(-0.5);
    expect(y).toBeCloseTo(-8.5);
  });

  it('ignores other keys', () => {
    const { svg } = renderRoom();
    fireEvent.keyDown(svg, { key: 'a' });
    expect(viewBoxOf(svg)).toEqual([-0.5, -8.5, 11, 9]);
  });

  it('zooms with the wheel, the keys and the buttons, and Fit restores the view', () => {
    const { svg } = renderRoom();
    fireEvent.wheel(svg, { deltaY: -200, clientX: 5, clientY: 4 });
    expect(viewBoxOf(svg)[2]).toBeLessThan(11);
    fireEvent.keyDown(svg, { key: '+' });
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    const zoomed = viewBoxOf(svg)[2];
    fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }));
    expect(viewBoxOf(svg)[2]).toBeGreaterThan(zoomed);
    fireEvent.click(screen.getByRole('button', { name: 'Fit' }));
    expect(viewBoxOf(svg)).toEqual([-0.5, -8.5, 11, 9]);
  });

  it('keeps the point under the cursor fixed when zooming', () => {
    const { svg } = renderRoom();
    fireEvent.wheel(svg, { deltaY: -300, clientX: 5.5, clientY: 4.5 });
    const [x, y, w, h] = viewBoxOf(svg);
    expect(x + (5.5 / 11) * w).toBeCloseTo(-0.5 + 5.5);
    expect(y + (4.5 / 9) * h).toBeCloseTo(-8.5 + 4.5);
  });
});

describe('RoomSvg: a drag is not a click', () => {
  it('does not select a mark that a drag started on', () => {
    const { mark, onSelect } = renderRoom();
    drag(mark, [50, 50], [70, 50]);
    fireEvent.click(mark);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('does not clear the selection when a drag ends on the empty map', () => {
    const { svg, onSelect } = renderRoom();
    drag(svg, [50, 50], [70, 60]);
    fireEvent.click(svg);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('still selects on a click, also after an earlier drag whose click never arrived', () => {
    const { svg, mark, onSelect } = renderRoom();
    drag(svg, [50, 50], [70, 60]);
    fireEvent.pointerDown(mark, { pointerId: 2, button: 0, clientX: 5, clientY: 5 });
    fireEvent.pointerUp(mark, { pointerId: 2, clientX: 5, clientY: 5 });
    fireEvent.click(mark);
    expect(onSelect).toHaveBeenCalledWith('entity:m');
  });

  it('treats a tiny movement as a click, not a pan', () => {
    const { svg, onSelect } = renderRoom();
    drag(svg, [50, 50], [51, 51]);
    fireEvent.click(svg);
    expect(viewBoxOf(svg)[0]).toBe(-0.5);
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});
