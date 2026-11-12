import { describe, expect, it } from 'vitest';
import { areaPath, gamePath, idToSegment, segmentToId } from './routeIds.ts';

const U1 = '00000000-0000-4000-8000-000000000001';
const U2 = '00000000-0000-4000-8000-000000000002';

describe('routeIds', () => {
  it('strips the type prefix for the path', () => {
    expect(idToSegment(`game:${U1}`)).toBe(U1);
    expect(gamePath(`game:${U1}`)).toBe(`/games/${U1}`);
    expect(areaPath(`game:${U1}`, `area:${U2}`)).toBe(`/games/${U1}/areas/${U2}`);
  });

  it('adds the prefix back for a bare segment', () => {
    expect(segmentToId('game', U1)).toBe(`game:${U1}`);
    expect(segmentToId('area', U2)).toBe(`area:${U2}`);
  });

  it('keeps an old-style segment that already has the right prefix', () => {
    expect(segmentToId('game', `game:${U1}`)).toBe(`game:${U1}`);
  });

  it('rejects a wrong prefix, an empty segment and a bare prefix', () => {
    expect(segmentToId('game', `area:${U2}`)).toBeNull();
    expect(segmentToId('area', undefined)).toBeNull();
    expect(segmentToId('area', '')).toBeNull();
    expect(segmentToId('area', 'area:')).toBeNull();
  });
});
