import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const entity = (id: string, state: Record<string, unknown>): Resource => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...state,
});

describe('RoomSvg', () => {
  it('draws entities at (x, -y), a fixed one like a mobile one', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          entity('entity:m', {
            entityKind: 'mob',
            name: 'orbiter',
            position: { x: 5, y: 7 },
          }),
          entity('entity:f', {
            entityKind: 'fixed',
            name: 'pillar',
            position: { x: 5, y: 4 },
          }),
        ]}
      />,
    );
    const circle = container.querySelector('[data-entity-id="entity:m"] circle');
    expect(circle).toHaveAttribute('cx', '5');
    expect(circle).toHaveAttribute('cy', '-7'); // y is flipped
    const fixed = container.querySelector('[data-entity-id="entity:f"] circle');
    expect(fixed).toHaveAttribute('cx', '5');
    expect(fixed).toHaveAttribute('cy', '-4');
    expect(container.querySelector('text')).toBeNull(); // no labels (#79)
  });

  it('scales by viewBox in world units and draws a border', () => {
    const { container } = render(<RoomSvg width={10} height={8} entities={[]} />);
    expect(container.querySelector('svg')?.getAttribute('viewBox')).toBe('-0.5 -8.5 11 9');
    expect(container.querySelector('.room-border')).toHaveAttribute('width', '10');
    expect(container.querySelector('.room-border')).toHaveAttribute('y', '-8'); // y is flipped
  });
});
