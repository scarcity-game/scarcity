import { describe, expect, it } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { MISSING, areaFacts, entityFacts, stairsFacts, nameOf, plainNumber } from './facts.ts';

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
    expect(entityFacts(diver).map((f) => [f.label, f.value])).toEqual([
      ['name', 'diver'],
      ['prototype', 'diver'],
      ['kind', 'npc'],
      ['mass', '70 kg'],
      ['diameter', '60 cm'],
      ['collision', 'solid: others cannot overlap it'],
      ['hp', '10'],
      ['speed', '500 mm/s'],
      ['position', '(3.25, 7) m'],
      ['action', 'step {"to":{"x":7.6,"y":5.5}}, game time 17:22 to 17:23'],
      ['controller', 'service diver'],
    ]);
  });

  it('keeps the exact values in each tooltip', () => {
    const titles = Object.fromEntries(entityFacts(diver).map((f) => [f.label, f.title]));
    expect(titles.mass).toBe('70000 g');
    expect(titles.diameter).toBe('0.6 m');
    expect(titles.speed).toBe('0.5 m/s');
    expect(titles.position).toBe('(3.25, 6.999999999999999) m');
    expect(titles.action).toBe('step {"to":{"x":7.6,"y":5.5}}, game time 1042 s to 1043 s');
    expect(titles.name).toBeUndefined();
  });

  it('cuts long numbers to 4 significant figures', () => {
    const v = valuesOf(resource({ mass: 5000000, radius: 0.123456, speed: 1.23456789 }));
    expect(v.mass).toBe('5 Mg');
    expect(v.diameter).toBe('24.69 cm');
    expect(v.speed).toBe('1.235 m/s');
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
  it('shows name, depth (absent reads as 0) and game time, and no size in s', () => {
    const area = {
      ...resource({ name: 'Level 2', time: 6.999999999999999 }),
      resourceKind: 'area',
    };
    expect(areaFacts(area)).toEqual([
      { label: 'name', value: 'Level 2' },
      { label: 'depth', value: '0' },
      { label: 'game time', value: '7 s', title: '6.999999999999999 s' },
    ]);
  });
});

describe('plainNumber and nameOf', () => {
  it('shows 4 significant figures and the exact number as the tooltip', () => {
    expect(plainNumber(0.1 + 0.2)).toEqual({ value: '0.3', title: '0.30000000000000004' });
    expect(plainNumber(3.71).value).toBe('3.71');
    expect(plainNumber(0.015).value).toBe('0.015');
    expect(plainNumber('x')).toEqual({ value: MISSING });
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
    expect(stairsFacts(resource({ prototype: 'stairs-up', to: 'entity:9' }))[1]?.value).toBe(
      'entity:9',
    );
  });

  it('names the level the stairs lead to when it is known', () => {
    const up = resource({ prototype: 'stairs-up', to: 'entity:9' });
    expect(stairsFacts(up, 'Level 2')[0]).toEqual({ label: 'leads', value: 'up to Level 2' });
  });
});

describe('a polygon entity', () => {
  const wall = resource({
    entityKind: 'fixed',
    prototype: 'stone-wall',
    material: 'stone',
    polygon: [
      { x: 0, y: 0 },
      { x: 2, y: 0 },
      { x: 2, y: 0.5 },
      { x: 0, y: 0.5 },
    ],
    position: { x: 1, y: 0.25 },
  });

  it('shows material, vertex count and bounding size instead of a diameter', () => {
    const facts = entityFacts(wall);
    expect(facts.map((f) => f.label)).not.toContain('diameter');
    expect(facts).toContainEqual({ label: 'material', value: 'stone' });
    expect(facts).toContainEqual({ label: 'vertices', value: '4' });
    expect(facts).toContainEqual({
      label: 'size',
      value: '2 m × 50 cm (bounding box)',
      title: '2 m × 0.5 m',
    });
  });
});
