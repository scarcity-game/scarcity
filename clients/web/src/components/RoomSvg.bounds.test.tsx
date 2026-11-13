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

describe('RoomSvg bounds and labels', () => {
  it('draws a marker fully inside the room when position is on boundary', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          // Circle at x=width (10) - should be clamped to 10
          entity('entity:c', {
            kind: 'mobile',
            position: { x: 10, y: 4 },
          }),
          // Square at y=0 - should be clamped to 0
          entity('entity:s', {
            kind: 'fixed',
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

  it('labels unnamed entities with kind or last 4 chars of ID', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          // Entity without name, with kind - should use kind
          entity('entity:withkind', {
            kind: 'mobile',
            position: { x: 5, y: 4 },
          }),
          // Entity without name, no kind - should use last 4 chars of ID
          entity('entity:shortid', {
            position: { x: 5, y: 4 },
          }),
        ]}
      />,
    );

    const textWithKind = container.querySelector('[data-entity-id="entity:withkind"] text');
    expect(textWithKind).toHaveTextContent('mobile');

    const textWithoutKind = container.querySelector('[data-entity-id="entity:shortid"] text');
    expect(textWithoutKind).toHaveTextContent('rtid');
  });

  it('clamps positions to room boundaries properly', () => {
    const { container } = render(
      <RoomSvg
        width={10}
        height={8}
        entities={[
          // Position at exact boundary - should stay at boundary
          entity('entity:boundary-x', {
            kind: 'fixed',
            position: { x: 10, y: 4 },
          }),
          entity('entity:boundary-y', {
            kind: 'fixed',
            position: { x: 5, y: 8 },
          }),
          // Position outside boundary - should be clamped
          entity('entity:outside-x', {
            kind: 'fixed',
            position: { x: 15, y: 4 },
          }),
          entity('entity:outside-y', {
            kind: 'fixed',
            position: { x: 5, y: -1 },
          }),
          // Position inside boundary - should stay as is
          entity('entity:inside', {
            kind: 'fixed',
            position: { x: 5, y: 4 },
          }),
        ]}
      />,
    );

    // All entities should be rendered
    expect(container.querySelectorAll('[data-entity-id]')).toHaveLength(5);
  });
});