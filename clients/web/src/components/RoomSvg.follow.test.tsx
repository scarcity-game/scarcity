import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const mouse = (x: number, y: number): Resource => ({
  id: 'entity:m',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: 'mouse',
  position: { x, y },
});

function viewBoxOf(svg: Element): [number, number, number, number] {
  const [x = NaN, y = NaN, w = NaN, h = NaN] = (svg.getAttribute('viewBox') ?? '')
    .split(' ')
    .map(Number);
  return [x, y, w, h];
}

/** The world point (y up) at the middle of the view. */
function centreOf(svg: Element): [number, number] {
  const [x, y, w, h] = viewBoxOf(svg);
  return [x + w / 2, 8 - (y + h / 2)];
}

function renderRoom(position: [number, number], followedId: string | null = 'entity:m') {
  const onStopFollow = vi.fn();
  const room = (at: [number, number], id: string | null) => (
    <RoomSvg
      width={10}
      height={8}
      entities={[mouse(...at)]}
      followedId={id}
      onStopFollow={onStopFollow}
    />
  );
  const { rerender } = render(room(position, followedId));
  const svg = screen.getByRole('img', { name: 'Room' });
  svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 11, height: 9 }) as DOMRect;
  return { svg, onStopFollow, moveTo: (at: [number, number]) => rerender(room(at, followedId)) };
}

describe('RoomSvg centres the followed entity', () => {
  it('centres it, magnified, and keeps it centred as it moves', () => {
    const { svg, moveTo } = renderRoom([2, 3]);
    expect(viewBoxOf(svg)[2]).toBeLessThan(11);
    expect(centreOf(svg)[0]).toBeCloseTo(2);
    expect(centreOf(svg)[1]).toBeCloseTo(3);
    moveTo([7, 5.5]);
    expect(centreOf(svg)[0]).toBeCloseTo(7);
    expect(centreOf(svg)[1]).toBeCloseTo(5.5);
  });

  it('shows the whole room when nothing is followed', () => {
    const { svg } = renderRoom([2, 3], null);
    expect(viewBoxOf(svg)).toEqual([-0.5, -0.5, 11, 9]);
  });

  it('keeps the user zoom while following, and centred', () => {
    const { svg, moveTo } = renderRoom([2, 3]);
    const before = viewBoxOf(svg)[2];
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(viewBoxOf(svg)[2]).toBeLessThan(before);
    moveTo([6, 2]);
    expect(centreOf(svg)[0]).toBeCloseTo(6);
    expect(centreOf(svg)[1]).toBeCloseTo(2);
  });

  it('stops following when the map is panned by hand', () => {
    const { svg, onStopFollow } = renderRoom([2, 3]);
    fireEvent.keyDown(svg, { key: 'ArrowRight' });
    expect(onStopFollow).toHaveBeenCalledTimes(1);
  });

  it('stops following on Fit, which shows the whole room', () => {
    const { onStopFollow } = renderRoom([2, 3]);
    fireEvent.click(screen.getByRole('button', { name: 'Fit' }));
    expect(onStopFollow).toHaveBeenCalledTimes(1);
  });

  it('does not move the view when the followed entity is not in this room', () => {
    const { svg } = renderRoom([2, 3], 'entity:elsewhere');
    expect(viewBoxOf(svg)).toEqual([-0.5, -0.5, 11, 9]);
  });
});
