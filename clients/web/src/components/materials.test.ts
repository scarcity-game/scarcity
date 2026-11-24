import { describe, expect, it } from 'vitest';
import type { SpriteManifest } from '../sprites/manifest.ts';
import { fillOf, lookOf, NEUTRAL_FILL, patternId } from './materials.ts';

const sprites = {
  patterns: {
    ashlar: { metres: 1.6, fallbackColor: '#111111' },
    timber: { metres: 1.6, fallbackColor: '#222222' },
  },
  materials: { granite: 'ashlar', oak: 'timber', ghost: 'missing' },
} as unknown as SpriteManifest;

describe('lookOf', () => {
  it("prefers the game's pattern", () => {
    expect(lookOf('granite', { granite: 'timber' }, sprites)).toEqual({
      pattern: 'timber',
      color: '#222222',
    });
  });

  it('falls back to the manifest default when the game has no map or no entry', () => {
    const ashlar = { pattern: 'ashlar', color: '#111111' };
    expect(lookOf('granite', undefined, sprites)).toEqual(ashlar);
    expect(lookOf('granite', { oak: 'timber' }, sprites)).toEqual(ashlar);
  });

  it('ignores a game pattern the manifest does not have', () => {
    expect(lookOf('oak', { oak: 'nope' }, sprites).pattern).toBe('timber');
  });

  it('is neutral grey for no material, an unknown material or a default naming no pattern', () => {
    for (const material of [undefined, 42, 'unknown', 'ghost', 'toString']) {
      expect(lookOf(material, { ghost: 'also-missing' }, sprites)).toEqual({ color: NEUTRAL_FILL });
    }
  });
});

describe('fillOf', () => {
  it("is a url to the room's pattern, or the flat colour", () => {
    expect(fillOf({ pattern: 'ashlar', color: '#111111' }, ':r1:')).toBe(
      `url(#${patternId(':r1:', 'ashlar')})`,
    );
    expect(patternId(':r1:', 'ashlar')).toBe('r1-ashlar');
    expect(fillOf({ color: NEUTRAL_FILL }, 'x')).toBe(NEUTRAL_FILL);
  });
});
