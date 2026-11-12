import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const entity = (id: string, state: Record<string, unknown>): Resource => ({
  id,
  type: 'entity',
  version: 1,
  children: [],
  state,
});

describe('RoomSvg', () => {
  it('draws a mobile entity as a circle at (x, height - y) and a fixed one as a square', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          entity('entity:m', {
            kind: 'mobile',
            name: 'orbiter',
            position: { x: 5, y: 7 },
          }),
          entity('entity:f', {
            kind: 'fixed',
            name: 'pillar',
            position: { x: 5, y: 4 },
          }),
        ]}
      />,
    );
    const circle = container.querySelector('[data-entity-id="entity:m"] circle');
    expect(circle).toHaveAttribute('cx', '5');
    expect(circle).toHaveAttribute('cy', '1'); // 8 - 7: y is flipped
    const square = container.querySelector('[data-entity-id="entity:f"] rect');
    expect(Number(square?.getAttribute('x')) + Number(square?.getAttribute('width')) / 2).toBe(5);
    expect(Number(square?.getAttribute('y')) + Number(square?.getAttribute('height')) / 2).toBe(4);
    expect(container.querySelector('[data-entity-id="entity:m"] text')).toHaveTextContent(
      'orbiter',
    );
  });

  it('scales by viewBox in world units and draws a border', () => {
    const { container } = render(<RoomSvg width={10} height={8} entities={[]} />);
    expect(container.querySelector('svg')?.getAttribute('viewBox')).toBe('-0.5 -0.5 11 9');
    expect(container.querySelector('.room-border')).toHaveAttribute('width', '10');
  });
});
