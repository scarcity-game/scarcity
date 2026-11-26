import type { KeyboardEvent, MouseEvent } from 'react';
import type { Resource } from '../resources/cache.ts';
import { polygonOf, toSvg, type Cells } from './shapes.ts';

/** The cells drawn as the floor plan: the `floor` ones (`docs/resources.md` "Cells"). */
export function floorCellsOf(cells: Cells | undefined): Resource[] {
  return [...(cells?.values() ?? [])].filter((cell) => cell.kind === 'floor');
}

function pointsText(cell: Resource): string | null {
  const polygon = polygonOf(cell);
  if (!polygon) return null;
  return polygon
    .map(toSvg)
    .map(({ x, y }) => `${x},${y}`)
    .join(' ');
}

interface FloorCellProps {
  cell: Resource;
  points: string;
  selected: boolean;
  onSelect?: (id: string | null) => void;
}

function activate(e: KeyboardEvent, select: () => void): void {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  e.preventDefault();
  select();
}

function FloorCell({ cell, points, selected, onSelect }: FloorCellProps) {
  const select = () => onSelect?.(cell.id);
  return (
    <g
      data-cell-id={cell.id}
      className="floor-cell"
      data-selected={selected ? 'true' : undefined}
      role="button"
      tabIndex={0}
      aria-label="floor cell"
      aria-pressed={selected}
      onClick={(e: MouseEvent) => {
        e.stopPropagation();
        select();
      }}
      onKeyDown={(e: KeyboardEvent) => activate(e, select)}
    >
      <polygon
        className="floor-cell-shape"
        points={points}
        fill="transparent"
        stroke="currentColor"
        strokeWidth={1}
        vectorEffect="non-scaling-stroke"
      />
      {selected && <SelectedRing points={points} />}
    </g>
  );
}

function SelectedRing({ points }: { points: string }) {
  return (
    <polygon
      className="selected-ring"
      points={points}
      fill="none"
      stroke="currentColor"
      strokeWidth={3}
      vectorEffect="non-scaling-stroke"
    />
  );
}

interface FloorCellsProps {
  cells?: Cells;
  selectedId?: string | null;
  onSelect?: (id: string | null) => void;
}

/** A faint outline of every floor cell, under the walls and entities; a click picks the cell. */
export function FloorCells({ cells, selectedId, onSelect }: FloorCellsProps) {
  return (
    <g className="floor-cells">
      {floorCellsOf(cells).map((cell) => {
        const points = pointsText(cell);
        return points ? (
          <FloorCell
            key={cell.id}
            cell={cell}
            points={points}
            selected={cell.id === selectedId}
            onSelect={onSelect}
          />
        ) : null;
      })}
    </g>
  );
}
