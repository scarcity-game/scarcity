import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MIN_RADIUS } from '../sprites/choice.ts';
import { RoomSvg } from './RoomSvg.tsx';

describe('RoomSvg with radius entities', () => {
  it('renders an entity with radius as a circle', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          {
            id: 'entity:1',
            resourceKind: 'entity',
            version: 1,
            children: [],
            name: 'pillar',
              position: { x: 5, y: 4 },
              radius: 0.5,
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:1"]');
    expect(entityGroup).not.toBeNull();

    const circle = entityGroup!.querySelector('circle');
    expect(circle).not.toBeNull();
    expect(circle?.getAttribute('r')).toBe('0.5');
    expect(circle?.getAttribute('cx')).toBe('5');
    expect(circle?.getAttribute('cy')).toBe('-4');
  });

  it('renders an entity without kind or name as a disc of the minimum radius', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          {
            id: 'entity:2',
            resourceKind: 'entity',
            version: 1,
            children: [],
            position: { x: 2, y: 3 },
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:2"]');
    expect(entityGroup).not.toBeNull();

    expect(entityGroup!.querySelector('rect')).toBeNull();
    const disc = entityGroup!.querySelector('circle.sprite-disc');
    expect(disc?.getAttribute('r')).toBe(String(MIN_RADIUS));
  });

  it('draws a mobile entity without a radius at the minimum radius', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          {
            id: 'entity:3',
            resourceKind: 'entity',
            version: 1,
            children: [],
            entityKind: 'mob',
              position: { x: 1, y: 1 },
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:3"]');
    expect(entityGroup).not.toBeNull();

    const circle = entityGroup!.querySelector('circle');
    expect(circle).not.toBeNull();
    expect(circle?.getAttribute('r')).toBe(String(MIN_RADIUS));
  });

  it('does not render an entity without position', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          {
            id: 'entity:4',
            resourceKind: 'entity',
            version: 1,
            children: [],
            name: 'no-position',
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:4"]');
    expect(entityGroup).toBeNull();
  });
});
