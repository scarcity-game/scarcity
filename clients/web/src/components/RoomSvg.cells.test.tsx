import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { RoomSvg } from './RoomSvg.tsx';

const resource = (id: string, resourceKind: string, state: Record<string, unknown>): Resource => ({
  id,
  resourceKind,
  version: 1,
  children: [],
  ...state,
});

const square = (x: number, y: number) => [
  { x, y },
  { x: x + 1, y },
  { x: x + 1, y: y + 1 },
  { x, y: y + 1 },
];

const wallCell = resource('cell:w', 'cell', { kind: 'wall', polygon: square(0, 0) });
const floorCell = resource('cell:f', 'cell', { kind: 'floor', polygon: square(1, 0) });
const rockCell = resource('cell:r', 'cell', { kind: 'rock', polygon: square(5, 5) });
const cells = new Map([wallCell, floorCell, rockCell].map((c) => [c.id, c]));

const wall = resource('entity:wall', 'entity', {
  entityKind: 'fixed',
  prototype: 'wall',
  name: 'granite wall',
  material: 'granite',
  cell: 'cell:w',
  position: { x: 0.5, y: 0.5 },
});

describe('RoomSvg walls from cells', () => {
  it("draws a wall with its cell's polygon and its material's pattern", () => {
    const { container } = render(
      <RoomSvg entities={[wall]} cells={cells} materialPatterns={{ granite: 'ashlar' }} />,
    );
    const polygon = container.querySelector('[data-entity-id="entity:wall"] polygon');
    expect(polygon).toHaveAttribute('points', '0,0 1,0 1,-1 0,-1');
    expect(polygon?.getAttribute('fill')).toMatch(/^url\(#.+-ashlar\)$/);
  });

  it('draws nothing for a wall whose cell is not loaded, not a disc', () => {
    const { container } = render(<RoomSvg entities={[wall]} cells={new Map()} />);
    expect(container.querySelector('[data-entity-id="entity:wall"]')).toBeNull();
  });

  it('fits the view to the walls, not to the floor or rock cells', () => {
    const { container } = render(<RoomSvg entities={[wall]} cells={cells} />);
    const [x, , w] = (container.querySelector('svg')?.getAttribute('viewBox') ?? '')
      .split(' ')
      .map(Number);
    expect([x, w]).toEqual([-0.5, 2]);
  });
});

describe('RoomSvg floor cells switch', () => {
  it('is off by default and draws only the floor cells when on', () => {
    const { container } = render(<RoomSvg entities={[wall]} cells={cells} />);
    expect(container.querySelector('[data-cell-id]')).toBeNull();
    const toggle = screen.getByRole('button', { name: 'Floor cells' });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const ids = [...container.querySelectorAll('[data-cell-id]')].map((g) =>
      g.getAttribute('data-cell-id'),
    );
    expect(ids).toEqual(['cell:f']);
    fireEvent.click(toggle);
    expect(container.querySelector('[data-cell-id]')).toBeNull();
  });

  it('has no switch when there are no floor cells', () => {
    render(<RoomSvg entities={[wall]} cells={new Map([[wallCell.id, wallCell]])} />);
    expect(screen.queryByRole('button', { name: 'Floor cells' })).toBeNull();
  });

  it('picks a cell on click and marks the selected one', () => {
    const onSelect = vi.fn();
    const { container, rerender } = render(
      <RoomSvg entities={[wall]} cells={cells} onSelect={onSelect} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Floor cells' }));
    fireEvent.click(container.querySelector('[data-cell-id="cell:f"]')!);
    expect(onSelect).toHaveBeenCalledExactlyOnceWith('cell:f');
    rerender(<RoomSvg entities={[wall]} cells={cells} selectedId="cell:f" />);
    expect(container.querySelector('[data-cell-id="cell:f"] .selected-ring')).not.toBeNull();
  });
});
