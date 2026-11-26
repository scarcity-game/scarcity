import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { entityFacts, factsOf, nameOf } from './facts.ts';

const cell = (id: string, state: Record<string, unknown>): Resource => ({
  id,
  resourceKind: 'cell',
  version: 1,
  children: [],
  polygon: [
    { x: 0, y: 0 },
    { x: 2, y: 0 },
    { x: 2, y: 1 },
    { x: 0, y: 1 },
  ],
  position: { x: 1, y: 0.5 },
  ...state,
});

const floor = cell('cell:f', {
  kind: 'floor',
  clearance: 0.4,
  neighbours: [
    { cell: 'cell:w' },
    { cell: 'cell:g', width: 2.4 },
    { cell: null },
    { cell: 'cell:x' },
  ],
});
const wallCell = cell('cell:w', { kind: 'wall', neighbours: [{ cell: 'cell:f' }] });
const floor2 = cell('cell:g', { kind: 'floor', clearance: 1, neighbours: [] });
const lookup = new Map([floor, wallCell, floor2].map((c) => [c.id, c]));

const values = (facts: { label: string; value: string }[]) =>
  Object.fromEntries(facts.map((f) => [f.label, f.value]));

describe('a cell picked on the map', () => {
  it('shows its kind, neighbours, clearance and widths', () => {
    const shown = values(factsOf(floor, lookup));
    expect(shown.kind).toBe('floor');
    expect(shown.neighbours).toBe('4: wall, floor 2.4 m, edge, not loaded');
    expect(shown.clearance).toBe('40 cm');
    expect(shown.width).toBe('2.4 m');
  });

  it('shows "—" for a clearance and widths it does not have', () => {
    const shown = values(factsOf(wallCell, lookup));
    expect(shown.clearance).toBe('—');
    expect(shown.width).toBe('—');
  });

  it('is named by its kind', () => {
    expect(nameOf(floor)).toBe('floor cell');
  });
});

describe('a wall with a cell', () => {
  const wall: Resource = {
    id: 'entity:wall',
    resourceKind: 'entity',
    version: 1,
    children: [],
    entityKind: 'fixed',
    prototype: 'wall',
    material: 'granite',
    cell: 'cell:w',
    position: { x: 1, y: 0.5 },
  };

  it("takes its shape from its cell and shows the cell's facts", () => {
    const shown = values(entityFacts(wall, lookup));
    expect(shown.vertices).toBe('4');
    expect(shown.size).toBe('2 m × 1 m (bounding box)');
    expect(shown['cell kind']).toBe('wall');
    expect(shown['cell neighbours']).toBe('1: floor');
    expect(shown['cell clearance']).toBe('—');
  });

  it('shows no cell facts while its cell is not loaded', () => {
    expect(entityFacts(wall).map((f) => f.label)).not.toContain('cell kind');
  });
});
