import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RoomSvg } from './RoomSvg.tsx';

describe('RoomSvg with radius entities', () => {
  it('renders an entity with radius as a circle', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          {
            id: 'entity:1',
            type: 'entity',
            version: 1,
            children: [],
            state: {
              name: 'pillar',
              position: { x: 5, y: 4 },
              radius: 0.5,
            },
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
    expect(circle?.getAttribute('cy')).toBe('4');
  });

  it('renders an entity without kind or name as a rect', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          {
            id: 'entity:2',
            type: 'entity',
            version: 1,
            children: [],
            state: {
              position: { x: 2, y: 3 },
            },
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:2"]');
    expect(entityGroup).not.toBeNull();

    const rect = entityGroup!.querySelector('rect');
    expect(rect).not.toBeNull();

    const circle = entityGroup!.querySelector('circle');
    expect(circle).toBeNull();
  });

  it('renders a mobile entity as a circle with default radius', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          {
            id: 'entity:3',
            type: 'entity',
            version: 1,
            children: [],
            state: {
              kind: 'mobile',
              position: { x: 1, y: 1 },
            },
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:3"]');
    expect(entityGroup).not.toBeNull();

    const circle = entityGroup!.querySelector('circle');
    expect(circle).not.toBeNull();
    expect(circle?.getAttribute('r')).toBe('0.3'); // MARK / 2 = 0.6 / 2
  });

  it('does not render an entity without position', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          {
            id: 'entity:4',
            type: 'entity',
            version: 1,
            children: [],
            state: {
              name: 'no-position',
            },
          },
        ]}
      />,
    );

    const entityGroup = container.querySelector('[data-entity-id="entity:4"]');
    expect(entityGroup).toBeNull();
  });
});
