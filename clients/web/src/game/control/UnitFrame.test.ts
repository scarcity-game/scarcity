import { describe, expect, it } from 'vitest';
import type { Resource } from '../../resources/cache.ts';
import { carriedItems } from './ItemsPanel.tsx';
import { unitStats } from './UnitFrame.tsx';

const entity = (keys: Record<string, unknown>): Resource => ({
  id: 'entity:e',
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...keys,
});

describe('unitStats', () => {
  it('shows speed, step reach and vision with their units', () => {
    expect(unitStats(entity({ speed: 1, stepReach: 5, vision: 10 }))).toEqual([
      { label: 'Speed', value: '1 m/s' },
      { label: 'Reach', value: '5 m' },
      { label: 'Vision', value: '10 m' },
    ]);
  });

  it('shows HP only on a damageable entity', () => {
    expect(unitStats(entity({ damageable: true, hp: 7.5 }))).toEqual([
      { label: 'HP', value: '7.5' },
    ]);
    expect(unitStats(entity({ damageable: false }))).toEqual([]);
  });

  it('leaves out a key that is not a number', () => {
    expect(unitStats(entity({ speed: 'fast' }))).toEqual([]);
  });
});

describe('carriedItems', () => {
  it('lists the item children the view holds, in order, and skips the rest', () => {
    const pebble = { id: 'item:p', resourceKind: 'item', version: 1, children: [], name: 'pebble' };
    const holder = entity({ children: ['item:missing', 'item:p', 'entity:x'] });
    const view = new Map<string, Resource>([
      ['item:p', pebble],
      ['entity:x', entity({ id: 'entity:x' })],
    ]);
    expect(carriedItems(holder, view)).toEqual([pebble]);
  });
});
