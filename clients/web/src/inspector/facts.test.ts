import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { MISSING, areaFacts, entityFacts, stairsFacts, formatNumber, nameOf } from './facts.ts';

const resource = (state: Record<string, unknown>): Resource => ({
  id: 'entity:1',
  resourceKind: 'entity',
  version: 3,
  children: [],
  ...state,
});

const diver = resource({
  entityKind: 'npc',
  prototype: 'diver',
  name: 'diver',
  mass: 70000.0,
  radius: 0.3,
  overlap: false,
  damageable: true,
  hp: 10.0,
  speed: 0.5,
  position: { x: 3.25, y: 6.999999999999999 },
  controller: { kind: 'service', principal: 'diver' },
  actionInProgress: {
    type: 'step',
    params: { to: { x: 7.6, y: 5.5 } },
    start: 1042.0,
    end: 1043.0,
    timing: 'end',
  },
});

function valuesOf(entity: Resource): Record<string, string> {
  return Object.fromEntries(entityFacts(entity).map((f) => [f.label, f.value]));
}

describe('entityFacts', () => {
  it('shows identity, SI physical attributes, the action and the controller, in order', () => {
    expect(entityFacts(diver)).toEqual([
      { label: 'name', value: 'diver' },
      { label: 'prototype', value: 'diver' },
      { label: 'kind', value: 'npc' },
      { label: 'mass', value: '70000 g' },
      { label: 'diameter', value: '0.6 m' },
      { label: 'collision', value: 'solid: others cannot overlap it' },
      { label: 'hp', value: '10' },
      { label: 'speed', value: '0.5 m/s' },
      { label: 'position', value: '(3.25, 7) m' },
      { label: 'action', value: 'step {"to":{"x":7.6,"y":5.5}}, game time 1042 to 1043' },
      { label: 'controller', value: 'service diver' },
    ]);
  });

  it('shows "—" for missing values and never makes one up', () => {
    const v = valuesOf(resource({ damageable: true }));
    for (const label of [
      'name',
      'prototype',
      'kind',
      'mass',
      'diameter',
      'hp',
      'speed',
      'position',
    ])
      expect(v[label]).toBe(MISSING);
    expect(v.controller).toBe(MISSING);
    expect(v.action).toBe('idle');
  });

  it('shows hp only when the entity is damageable', () => {
    expect(valuesOf(resource({ hp: 5 }))).not.toHaveProperty('hp');
    expect(valuesOf(resource({ damageable: false }))).not.toHaveProperty('hp');
  });

  it('reads overlap as false when absent, as the contract says', () => {
    expect(valuesOf(resource({})).collision).toBe('solid: others cannot overlap it');
    expect(valuesOf(resource({ overlap: true })).collision).toBe('others can overlap it');
  });

  it('names a behaviour when there is no controller, and a user by sub', () => {
    expect(valuesOf(resource({ behaviour: { kind: 'random' } })).controller).toBe(
      'behaviour: random',
    );
    expect(valuesOf(resource({ controller: { kind: 'user', sub: 'u-1' } })).controller).toBe(
      'user u-1',
    );
  });
});

describe('areaFacts', () => {
  it('shows name, depth (absent reads as 0), size in m and game time in s', () => {
    const area = {
      ...resource({ name: 'Level 2', width: 10, height: 8, time: 6.999999999999999 }),
      resourceKind: 'area',
    };
    expect(areaFacts(area)).toEqual([
      { label: 'name', value: 'Level 2' },
      { label: 'depth', value: '0' },
      { label: 'size', value: '10 × 8 m' },
      { label: 'game time', value: '7 s' },
    ]);
  });
});

describe('formatNumber and nameOf', () => {
  it('drops floating-point noise only', () => {
    expect(formatNumber(0.1 + 0.2)).toBe('0.3');
    expect(formatNumber(3.71)).toBe('3.71');
    expect(formatNumber(0.015)).toBe('0.015');
  });

  it('names by name, else prototype, else id', () => {
    expect(nameOf(resource({ name: 'pillar', prototype: 'p' }))).toBe('pillar');
    expect(nameOf(resource({ prototype: 'mouse' }))).toBe('mouse');
    expect(nameOf(resource({}))).toBe('entity:1');
  });

  it('shows the direction of stairs and their pairing, and nothing for other entities', () => {
    expect(stairsFacts(diver)).toEqual([]);
    const down = resource({ prototype: 'stairs-down', to: null });
    expect(stairsFacts(down)).toEqual([
      { label: 'leads', value: 'down (to another level)' },
      { label: 'paired stairs', value: 'not made yet' },
    ]);
    expect(stairsFacts(resource({ prototype: 'stairs-up', to: 'entity:9' }))[1]?.value).toBe('entity:9');
  });
});
