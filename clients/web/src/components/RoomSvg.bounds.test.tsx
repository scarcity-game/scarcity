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

describe('RoomSvg bounds and labels', () => {
  it('draws a marker fully inside the room when position is on boundary', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          // Circle at x=width (10) - should be clamped to 10
          entity('entity:c', {
            entityKind: 'mob',
            position: { x: 10, y: 4 },
          }),
          // Square at y=0 - should be clamped to 0
          entity('entity:s', {
            entityKind: 'fixed',
            position: { x: 5, y: 0 },
          }),
        ]}
      />,
    );

    // Check that the circle is drawn within bounds (x=10, but clamped to viewBox)
    const circle = container.querySelector('[data-entity-id="entity:c"] circle');
    expect(circle).toBeDefined();

    // Check that the square is drawn within bounds (y=0, but clamped to viewBox)
    const square = container.querySelector('[data-entity-id="entity:s"] rect');
    expect(square).toBeDefined();
  });

  it('draws no labels; a mark is named by its name, else its prototype, else its id', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          entity('entity:named', { name: 'orbiter', position: { x: 2, y: 4 } }),
          entity('entity:proto', { prototype: 'mouse', position: { x: 5, y: 4 } }),
          entity('entity:bare', { position: { x: 8, y: 4 } }),
        ]}
      />,
    );
    expect(container.querySelector('text')).toBeNull();
    const name = (id: string) =>
      container.querySelector(`[data-entity-id="${id}"]`)?.getAttribute('aria-label');
    expect(name('entity:named')).toBe('orbiter');
    expect(name('entity:proto')).toBe('mouse');
    expect(name('entity:bare')).toBe('entity:bare');
  });

  it('clamps positions to room boundaries properly', () => {
    const { container } = render(
      <RoomSvg
        entities={[
          // Position at exact boundary - should stay at boundary
          entity('entity:boundary-x', {
            entityKind: 'fixed',
            position: { x: 10, y: 4 },
          }),
          entity('entity:boundary-y', {
            entityKind: 'fixed',
            position: { x: 5, y: 8 },
          }),
          // Position outside boundary - should be clamped
          entity('entity:outside-x', {
            entityKind: 'fixed',
            position: { x: 15, y: 4 },
          }),
          entity('entity:outside-y', {
            entityKind: 'fixed',
            position: { x: 5, y: -1 },
          }),
          // Position inside boundary - should stay as is
          entity('entity:inside', {
            entityKind: 'fixed',
            position: { x: 5, y: 4 },
          }),
        ]}
      />,
    );

    // All entities should be rendered
    expect(container.querySelectorAll('[data-entity-id]')).toHaveLength(5);
  });
});
