import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { boundsOfEntity, boundsOfShapes, polygonOf, svgBoxOf } from './shapes.ts';

const entity = (state: Record<string, unknown>): Resource => ({
  id: 'entity:x',
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...state,
});

const wall = entity({
  prototype: 'stone-wall',
  material: 'stone',
  polygon: [
    { x: -1, y: 0 },
    { x: 11, y: 0 },
    { x: 11, y: 0.5 },
    { x: -1, y: 0.5 },
  ],
  position: { x: 5, y: 0.25 },
});
const mouse = entity({ position: { x: 3, y: 4 }, radius: 0.5 });

describe('bounding box of shapes', () => {
  it('uses position ± radius for a circle and the vertices for a polygon', () => {
    expect(boundsOfEntity(mouse)).toEqual({ minX: 2.5, minY: 3.5, maxX: 3.5, maxY: 4.5 });
    expect(boundsOfEntity(wall)).toEqual({ minX: -1, minY: 0, maxX: 11, maxY: 0.5 });
  });

  it('covers circles and polygons together', () => {
    const far = entity({ position: { x: 20, y: 9 }, radius: 1 });
    expect(boundsOfShapes([wall, mouse, far])).toEqual({ minX: -1, minY: 0, maxX: 21, maxY: 10 });
  });

  it('falls back to the legacy width and height, and is null with nothing', () => {
    expect(boundsOfShapes([], { width: 10, height: 8 })).toEqual({
      minX: 0,
      minY: 0,
      maxX: 10,
      maxY: 8,
    });
    expect(boundsOfShapes([])).toBeNull();
  });

  it('flips y and adds the margin in the SVG box', () => {
    const bounds = boundsOfShapes([wall, mouse]);
    expect(bounds && svgBoxOf(bounds, 0.5)).toEqual({ x: -1.5, y: -5, w: 13, h: 5.5 });
  });

  it('reads a polygon only when it is a list of at least three points', () => {
    expect(polygonOf(wall)).toHaveLength(4);
    expect(polygonOf(mouse)).toBeNull();
    expect(polygonOf(entity({ polygon: [{ x: 0, y: 0 }] }))).toBeNull();
  });
});
