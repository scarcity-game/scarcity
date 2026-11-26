import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

function entity(id: string, prototype: string, entityKind: string, x: number): Resource {
  return {
    id,
    resourceKind: 'entity',
    version: 1,
    children: [],
    entityKind,
    prototype,
    name: prototype,
    position: { x, y: 2 },
    radius: 0.04,
  };
}

const entities = [
  entity('entity:mouse', 'mouse', 'mob', 1),
  entity('entity:diver', 'humanoid', 'mob', 2),
  entity('entity:stairs', 'stairs', 'fixed', 3),
  entity('entity:pillar', 'pillar', 'fixed', 4),
  entity('entity:plain', 'unknown-thing', 'fixed', 5),
];

/** #393: a press on the sprite's own `<use>` was lost when the animation swapped the frame. */
describe('RoomSvg hit area', () => {
  it.each(entities.map((e) => [e.name, e.id] as const))(
    'selects %s from a click on its centre, where a stable disc is the only hit target',
    (_name, id) => {
      const onSelect = vi.fn();
      const { container } = render(<RoomSvg entities={entities} onSelect={onSelect} />);
      const mark = container.querySelector(`[data-entity-id="${id}"]`)!;
      const disc = mark.querySelector('circle')!;
      const sprite = mark.querySelector('.sprite');
      if (sprite) expect(sprite.getAttribute('pointer-events')).toBe('none');
      fireEvent.click(disc);
      expect(onSelect).toHaveBeenCalledExactlyOnceWith(id);
    },
  );

  it('keeps the animated sprites out of the hit test, whatever their frame', () => {
    const { container } = render(<RoomSvg entities={entities} />);
    const sprites = container.querySelectorAll('.sprite');
    expect(sprites.length).toBeGreaterThan(0);
    for (const sprite of sprites) expect(sprite.getAttribute('pointer-events')).toBe('none');
  });
});
