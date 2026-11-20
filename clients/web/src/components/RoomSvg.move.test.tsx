import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const entity = (id: string, state: Record<string, unknown>): Resource => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: id.slice(-4),
  ...state,
});

const stepping = (id: string, x: number, y: number, to: { x: number; y: number }) =>
  entity(id, {
    position: { x, y },
    actionInProgress: { type: 'step', params: { to }, start: 10, end: 14, timing: 'channel' },
  });

const arrows = (entities: Resource[]) =>
  render(<RoomSvg width={10} height={8} entities={entities} />).container.querySelectorAll('line.move-arrow');

describe('RoomSvg move arrows', () => {
  it('draws an arrow from a stepping entity to where it is going, y flipped', () => {
    const [line] = arrows([stepping('entity:a', 8, 5, { x: 8, y: 7 })]);
    expect(line).toHaveAttribute('data-entity', 'entity:a');
    expect(line).toHaveAttribute('x1', '8');
    expect(line).toHaveAttribute('y1', '3');
    expect(line).toHaveAttribute('x2', '8');
    expect(line).toHaveAttribute('y2', '1');
    expect(line?.getAttribute('marker-end')).toMatch(/^url\(#.+\)$/);
  });

  it('shrinks as the entity gets there: it starts where the entity is now', () => {
    const [line] = arrows([stepping('entity:a', 8, 6.5, { x: 8, y: 7 })]);
    expect(line).toHaveAttribute('y1', '1.5');
  });

  it('draws none for an idle entity, a wait, or a step that has arrived', () => {
    expect(
      arrows([
        entity('entity:idle', { position: { x: 1, y: 1 } }),
        entity('entity:wait', {
          position: { x: 2, y: 2 },
          actionInProgress: { type: 'wait', params: { duration: 1 }, start: 10, end: 11, timing: 'none' },
        }),
        stepping('entity:there', 4, 4, { x: 4, y: 4 }),
      ]),
    ).toHaveLength(0);
  });

  it('draws one arrow per mover', () => {
    expect(
      arrows([stepping('entity:a', 1, 1, { x: 3, y: 1 }), stepping('entity:b', 5, 5, { x: 5, y: 2 })]),
    ).toHaveLength(2);
  });
});
