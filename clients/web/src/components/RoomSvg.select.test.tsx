import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const mouse: Resource = {
  id: 'entity:m',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: 'mouse',
  position: { x: 2, y: 2 },
};

function renderRoom(selectedId: string | null = null) {
  const onSelect = vi.fn();
  const onHover = vi.fn();
  const view = render(
    <RoomSvg
      width={10}
      height={8}
      entities={[mouse]}
      selectedId={selectedId}
      onSelect={onSelect}
      onHover={onHover}
    />,
  );
  return { ...view, onSelect, onHover, mark: screen.getByRole('button', { name: 'mouse' }) };
}

describe('RoomSvg selection and hover', () => {
  it('selects a mark on click, and clears on a click on the empty map', () => {
    const { onSelect, mark } = renderRoom();
    fireEvent.click(mark);
    expect(onSelect).toHaveBeenLastCalledWith('entity:m');
    expect(onSelect).toHaveBeenCalledTimes(1); // the click didn't reach the map too
    fireEvent.click(screen.getByRole('img', { name: 'Room' }));
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('selects a focused mark with Enter or Space', () => {
    const { onSelect, mark } = renderRoom();
    fireEvent.keyDown(mark, { key: 'Enter' });
    fireEvent.keyDown(mark, { key: ' ' });
    fireEvent.keyDown(mark, { key: 'a' });
    expect(onSelect).toHaveBeenCalledTimes(2);
  });

  it('reports hover and focus with the mark, and leaving with null', () => {
    const { onHover, mark } = renderRoom();
    fireEvent.pointerEnter(mark);
    expect(onHover).toHaveBeenLastCalledWith('entity:m', mark);
    fireEvent.pointerLeave(mark);
    expect(onHover).toHaveBeenLastCalledWith(null, null);
    fireEvent.focus(mark);
    expect(onHover).toHaveBeenLastCalledWith('entity:m', mark);
  });

  it('rings the selected mark and marks it pressed', () => {
    const { mark, container } = renderRoom('entity:m');
    expect(mark).toHaveAttribute('aria-pressed', 'true');
    expect(container.querySelector('.selected-ring')).not.toBeNull();
  });
});
