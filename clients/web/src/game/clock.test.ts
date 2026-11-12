import { describe, expect, it } from 'vitest';
import { currentGameTime } from './clock.ts';

const anchor = { gameTime: 1042.0, wallTime: 1794441600000, rate: 1.0 };

describe('currentGameTime', () => {
  it('returns the anchor game time at the anchor wall time', () => {
    expect(currentGameTime(anchor, anchor.wallTime)).toBe(1042);
  });
  it('advances by rate per wall second', () => {
    expect(currentGameTime(anchor, anchor.wallTime + 370)).toBeCloseTo(1042.37, 9);
    expect(currentGameTime(anchor, anchor.wallTime + 10_000)).toBeCloseTo(1052, 9);
  });
  it('stands still at rate 0', () => {
    const c = { ...anchor, rate: 0 };
    expect(currentGameTime(c, anchor.wallTime + 99_000)).toBe(1042);
  });
  it('runs twice as fast at rate 2', () => {
    const c = { ...anchor, rate: 2.0 };
    expect(currentGameTime(c, anchor.wallTime + 1500)).toBeCloseTo(1045, 9);
  });
  it.each([
    ['undefined', undefined],
    ['null', null],
    ['string', 'clock'],
    ['number', 5],
    ['missing fields', {}],
    ['NaN gameTime', { ...anchor, gameTime: NaN }],
    ['Infinity wallTime', { ...anchor, wallTime: Infinity }],
    ['-Infinity rate', { ...anchor, rate: -Infinity }],
    ['string field', { ...anchor, rate: '1' }],
  ])('gives null for %s', (_n, clock) => {
    expect(currentGameTime(clock, anchor.wallTime)).toBeNull();
  });
});
