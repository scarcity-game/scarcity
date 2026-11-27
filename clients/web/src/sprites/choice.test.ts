import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import {
  MIN_RADIUS,
  NEUTRAL_COLOR,
  appearanceOf,
  drawnRadius,
  effectIcon,
  frameIndex,
  lookOf,
  overlayPlacement,
  phaseOf,
  spriteScale,
  stateOf,
} from './choice.ts';
import { manifest } from './manifest.ts';

const entity = (fields: Record<string, unknown> = {}): Resource => ({
  id: 'entity:1',
  resourceKind: 'entity',
  version: 1,
  children: [],
  ...fields,
});

const doing = (type: string) => ({ actionInProgress: { type, start: 1, end: 2 } });
const ALL = manifest.categories.humanoid!.states;

describe('stateOf: from actionInProgress', () => {
  it.each([
    ['step', 'moving'],
    ['fly', 'moving'],
    ['throw', 'acting'],
    ['traverse', 'acting'],
    ['wait', 'idle'],
    ['decay', 'idle'],
  ])('%s is %s', (type, state) => {
    expect(stateOf(entity(doing(type)), undefined, ALL)).toBe(state);
  });

  it('is idle with no action in progress', () => {
    expect(stateOf(entity(), undefined, ALL)).toBe('idle');
  });
});

describe('stateOf: from the log', () => {
  it('shows acted-upon for the target of an effect, even while moving', () => {
    const e = entity(doing('step'));
    expect(stateOf(e, new Map([['entity:1', 'damage']]), ALL)).toBe('acted-upon');
    expect(stateOf(e, new Map([['entity:2', 'damage']]), ALL)).toBe('moving');
  });

  it('falls back to idle when the category lacks the state, then to static', () => {
    const stone = manifest.categories.stone!.states;
    expect(stateOf(entity(doing('throw')), undefined, stone)).toBe('idle');
    expect(stateOf(entity(), new Map([['entity:1', 'damage']]), stone)).toBe('idle');
    const pillar = manifest.categories.pillar!.states;
    expect(stateOf(entity(doing('step')), undefined, pillar)).toBe('static');
  });
});

describe('appearanceOf', () => {
  it("is the prototype's default appearance, with a colour for every slot of its category", () => {
    const appearance = appearanceOf(entity({ prototype: 'goblin' }), manifest)!;
    expect(appearance).toBe(manifest.appearances.goblin);
    const slots = Object.keys(manifest.categories[appearance.category]!.slots);
    expect(Object.keys(appearance.colours).sort()).toEqual(slots.sort());
  });

  it('is none for an entity without a prototype, or one the manifest lacks', () => {
    expect(appearanceOf(entity(), manifest)).toBeUndefined();
    expect(appearanceOf(entity({ prototype: 'no-such-prototype' }), manifest)).toBeUndefined();
  });
});

describe('lookOf: fallback', () => {
  it('gives every appearance of the manifest its sprite', () => {
    for (const prototype of Object.keys(manifest.appearances)) {
      expect(lookOf(entity({ prototype }), manifest).kind).toBe('sprite');
    }
  });

  it('draws a prototype that names a category as a disc in its colour, and reports it', () => {
    const gap = vi.fn();
    const look = lookOf(entity({ prototype: 'humanoid' }), manifest, gap);
    expect(look).toEqual({ kind: 'disc', color: manifest.categories.humanoid!.fallbackColor });
    expect(gap).toHaveBeenCalledTimes(1);
  });

  it('draws any other prototype, and an entity without one, as a neutral disc', () => {
    const gap = vi.fn();
    const neutral = { kind: 'disc', color: NEUTRAL_COLOR };
    expect(lookOf(entity({ prototype: 'no-such-prototype' }), manifest, gap)).toEqual(neutral);
    expect(lookOf(entity(), manifest, gap)).toEqual(neutral);
    expect(gap).toHaveBeenCalledTimes(2);
  });

  it('draws an appearance of an unknown category as a neutral disc', () => {
    const ghost = { ...manifest.appearances.pebble!, category: 'no-such-category' };
    const odd = { ...manifest, appearances: { ...manifest.appearances, ghost } };
    const gap = vi.fn();
    expect(lookOf(entity({ prototype: 'ghost' }), odd, gap)).toEqual({
      kind: 'disc',
      color: NEUTRAL_COLOR,
    });
    expect(gap).toHaveBeenCalledWith(expect.stringContaining('no-such-category'));
  });

  it('draws the placeholder as a neutral disc without reporting a gap', () => {
    const gap = vi.fn();
    expect(lookOf(entity({ prototype: 'placeholder' }), manifest, gap).kind).toBe('disc');
    expect(gap).not.toHaveBeenCalled();
  });
});

describe('scaling by radius', () => {
  it('scales the sprite circle (radius 16 of the grid) to the entity radius', () => {
    expect(spriteScale(0.5, 32)).toBe(0.5 / 16);
    expect(spriteScale(2, 32)).toBe(2 / 16);
  });

  it('never draws under the minimum, so a pebble (0.015 m) stays visible', () => {
    expect(drawnRadius(0.015)).toBe(MIN_RADIUS);
    expect(drawnRadius(undefined)).toBe(MIN_RADIUS);
    expect(drawnRadius(-1)).toBe(MIN_RADIUS);
    expect(drawnRadius(0.3)).toBe(0.3);
  });
});

describe('frames', () => {
  const state = { frames: ['a', 'b', 'c', 'd'], frameMs: 120 };

  it('loops through the frames at frameMs', () => {
    const at = [0, 119, 120, 360, 480, 600].map((ms) => frameIndex(state, ms));
    expect(at).toEqual([0, 0, 1, 3, 0, 1]);
  });

  it('shows frame 0 of a static state, and shifts by phase', () => {
    expect(frameIndex({ frames: ['s'], frameMs: 0 }, 5000)).toBe(0);
    expect(frameIndex(state, 0, 240)).toBe(2);
  });

  it('gives an id a stable phase inside the span', () => {
    expect(phaseOf('entity:1', 480)).toBe(phaseOf('entity:1', 480));
    expect(phaseOf('entity:1', 480)).toBeLessThan(480);
    expect(phaseOf('x', 0)).toBe(0);
  });
});

describe('overlayPlacement', () => {
  it('draws an unanchored overlay where it is', () => {
    expect(overlayPlacement('overlay.hood', {})).toEqual({ symbol: 'overlay.hood', transform: undefined });
  });

  it('moves an anchored overlay from its rest point to the frame anchor, turned by the angle difference', () => {
    const hood = { symbol: 'overlay.hood', anchor: 'head', at: [1, -9.5] as [number, number] };
    expect(overlayPlacement(hood, { head: [1, -9.5] })?.transform).toBe('translate(1 -9.5) translate(-1 9.5)');
    expect(overlayPlacement(hood, { head: [3, -8, 20] })?.transform).toBe('translate(3 -8) rotate(20) translate(-1 9.5)');
  });

  it('leaves out an overlay whose anchor the frame lacks', () => {
    expect(overlayPlacement({ symbol: 'overlay.hood', anchor: 'head', at: [0, 0] }, {})).toBeNull();
  });
});

describe('effectIcon', () => {
  it('picks the kind of the effect type, else the default kind', () => {
    const m = { ...manifest, effects: { ...manifest.effects, types: { fireball: 'magic' } } };
    expect(effectIcon(m, 'fireball').symbol).toBe('effect.magic');
    expect(effectIcon(m, 'melee-attack').symbol).toBe('effect.physical');
  });
});
