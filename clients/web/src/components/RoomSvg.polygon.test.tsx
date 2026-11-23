import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const entity = (id: string, state: Record<string, unknown>): Resource => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...state,
});

const wall = entity('entity:wall', {
  entityKind: 'fixed',
  prototype: 'stone-wall',
  name: 'stone wall',
  material: 'stone',
  polygon: [
    { x: 0, y: 0 },
    { x: 20, y: 0 },
    { x: 20, y: 1 },
    { x: 0, y: 1 },
  ],
  position: { x: 10, y: 0.5 },
});
const mouse = entity('entity:m', { name: 'mouse', position: { x: 5, y: 4 }, radius: 0.04 });

describe('RoomSvg polygon entities', () => {
  it('draws a polygon entity as a polygon, filled by material, beneath the circles', () => {
    const { container } = render(<RoomSvg entities={[mouse, wall]} />);
    const polygon = container.querySelector('[data-entity-id="entity:wall"] polygon');
    expect(polygon).toHaveAttribute('points', '0,0 20,0 20,-1 0,-1');
    expect(polygon?.getAttribute('fill')).toBe('#7b7f87');
    const marks = [...container.querySelectorAll('[data-entity-id]')].map((g) =>
      g.getAttribute('data-entity-id'),
    );
    expect(marks).toEqual(['entity:wall', 'entity:m']);
    expect(container.querySelector('[data-entity-id="entity:wall"] circle')).toBeNull();
  });

  it('fits the view to the shapes, without a width or height', () => {
    const { container } = render(<RoomSvg entities={[mouse, wall]} />);
    const [x, y, w, h] = (container.querySelector('svg')?.getAttribute('viewBox') ?? '')
      .split(' ')
      .map(Number);
    expect([x, w]).toEqual([-0.5, 21]);
    expect(y).toBeCloseTo(-4.54);
    expect(h).toBeCloseTo(5.04);
    expect(container.querySelector('.room-border')).toBeNull();
  });

  it('selects on click, shows the selection ring and highlights', () => {
    const onSelect = vi.fn();
    const { container, rerender } = render(<RoomSvg entities={[wall]} onSelect={onSelect} />);
    fireEvent.click(screen.getByRole('button', { name: 'stone wall' }));
    expect(onSelect).toHaveBeenCalledWith('entity:wall');
    rerender(<RoomSvg entities={[wall]} selectedId="entity:wall" />);
    expect(container.querySelector('polygon.selected-ring')).not.toBeNull();
    rerender(<RoomSvg entities={[wall]} highlightId="entity:wall" />);
    expect(container.querySelector('polygon.highlight-ring')).not.toBeNull();
  });
});
