import { exactQuantity, formatQuantity } from '../format/units.ts';
import { boundsOfEntity, polygonOf, positionOf as pointOf, sizeOf } from '../components/shapes.ts';
import type { Resource } from '../resources/cache.ts';
import { MISSING, positionOf, quantityOf, type Fact } from './facts.ts';

/** What the panel shows for cells: the cells it can look up, by id. */
export type CellLookup = ReadonlyMap<string, Resource>;

interface Neighbour {
  cell: string | null;
  width?: number;
}

function neighboursOf(cell: Resource): Neighbour[] {
  const list = cell.neighbours;
  if (!Array.isArray(list)) return [];
  return list.map((entry: { cell?: unknown; width?: unknown }) => ({
    cell: typeof entry?.cell === 'string' ? entry.cell : null,
    width: typeof entry?.width === 'number' ? entry.width : undefined,
  }));
}

/** One edge: the kind of the cell across it ("edge" where the tiling ends), and the width of a floor-floor edge. */
function neighbourText(neighbour: Neighbour, lookup: CellLookup | undefined): string {
  const loaded = neighbour.cell === null ? undefined : lookup?.get(neighbour.cell)?.kind;
  const kind =
    neighbour.cell === null ? 'edge' : typeof loaded === 'string' ? loaded : 'not loaded';
  return neighbour.width === undefined
    ? kind
    : `${kind} ${formatQuantity(neighbour.width, 'length')}`;
}

function neighboursFact(cell: Resource, lookup: CellLookup | undefined, label: string): Fact {
  const list = neighboursOf(cell);
  if (list.length === 0) return { label, value: MISSING };
  return {
    label,
    value: `${list.length}: ${list.map((n) => neighbourText(n, lookup)).join(', ')}`,
  };
}

/** The widths of the edges that have one (between two floor cells), as one row. */
function widthFact(cell: Resource, label: string): Fact {
  const widths = neighboursOf(cell).flatMap((n) => (n.width === undefined ? [] : [n.width]));
  if (widths.length === 0) return { label, value: MISSING };
  return {
    label,
    value: widths.map((w) => formatQuantity(w, 'length')).join(', '),
    title: widths.map((w) => exactQuantity(w, 'length')).join(', '),
  };
}

function prefixed(prefix: string, name: string): string {
  return prefix === '' ? name : `${prefix} ${name}`;
}

/** A cell's kind, neighbours, clearance and edge widths (`docs/resources.md` "Cells"); `prefix` names them for a wall's cell. */
export function cellKeyFacts(cell: Resource, lookup?: CellLookup, prefix = ''): Fact[] {
  const kind = typeof cell.kind === 'string' ? cell.kind : MISSING;
  return [
    { label: prefixed(prefix, 'kind'), value: kind },
    neighboursFact(cell, lookup, prefixed(prefix, 'neighbours')),
    { label: prefixed(prefix, 'clearance'), ...quantityOf(cell.clearance, 'length') },
    widthFact(cell, prefixed(prefix, 'width')),
  ];
}

/** The panel's rows for a cell picked on the map. */
export function cellFacts(cell: Resource, lookup?: CellLookup): Fact[] {
  const polygon = polygonOf(cell);
  const bounds = boundsOfEntity(cell);
  const size = bounds ? sizeOf(bounds) : null;
  return [
    ...cellKeyFacts(cell, lookup),
    { label: 'vertices', value: polygon ? String(polygon.length) : MISSING },
    ...(size
      ? [
          {
            label: 'size',
            value: `${formatQuantity(size.width, 'length')} × ${formatQuantity(size.height, 'length')} (bounding box)`,
          },
        ]
      : []),
    { label: 'position', ...positionOf(pointOf(cell) ? cell.position : undefined) },
  ];
}
