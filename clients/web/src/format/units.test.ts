import { describe, expect, it } from 'vitest';
import {
  compactJson,
  exactJson,
  exactPoint,
  exactQuantity,
  formatNumber,
  formatPoint,
  formatQuantity,
} from './units.ts';

describe('formatNumber', () => {
  it('keeps 4 significant figures without padding', () => {
    expect(formatNumber(0.5)).toBe('0.5');
    expect(formatNumber(1234.567)).toBe('1235');
    expect(formatNumber(0.000123456)).toBe('0.0001235');
    expect(formatNumber(4.2)).toBe('4.2');
    expect(formatNumber(7)).toBe('7');
    expect(formatNumber(6.999999999999999)).toBe('7');
    expect(formatNumber(-1234.567)).toBe('-1235');
    expect(formatNumber(0)).toBe('0');
  });
});

describe('formatQuantity: mass (grams)', () => {
  it.each([
    [0.5, '500 mg'],
    [5, '5 g'],
    [1500, '1.5 kg'],
    [5000000, '5 Mg'],
    [999.96, '1 kg'],
    [7e9, '7 Gg'],
    [2e12, '2000 Gg'],
    [0, '0 g'],
    [0.00001, '0.01 mg'],
  ])('%d g is %s', (value, text) => expect(formatQuantity(value, 'mass')).toBe(text));
});

describe('formatQuantity: length, speed, time', () => {
  it.each([
    [0.004, 'length', '4 mm'],
    [0.04, 'length', '4 cm'],
    [2.5, 'length', '2.5 m'],
    [12345, 'length', '12.35 km'],
    [0.5, 'speed', '500 mm/s'],
    [1.5, 'speed', '1.5 m/s'],
    [3000, 'speed', '3 km/s'],
    [0.25, 'time', '250 ms'],
    [45, 'time', '45 s'],
    [120, 'time', '2 min'],
    [7200, 'time', '2 h'],
    [172800, 'time', '2 d'],
    [0, 'time', '0 s'],
  ] as const)('%d is %s', (value, quantity, text) =>
    expect(formatQuantity(value, quantity)).toBe(text),
  );

  it('marks a rounded time with "~"', () => {
    expect(formatQuantity(2149, 'time')).toBe('~35.82 min');
    expect(formatQuantity(1.23456, 'time')).toBe('~1.235 s');
  });

  it('does not mark a rounded length', () => {
    expect(formatQuantity(1.23456, 'length')).toBe('1.235 m');
  });
});

describe('exact values', () => {
  it('keeps every digit in the server unit', () => {
    expect(exactQuantity(2149, 'time')).toBe('2149 s');
    expect(exactQuantity(5000000, 'mass')).toBe('5000000 g');
    expect(exactQuantity(0.123456789, 'speed')).toBe('0.123456789 m/s');
    expect(exactPoint(1.23456, 2)).toBe('(1.23456, 2) m');
  });
});

describe('points and json', () => {
  it('shares one unit between both coordinates', () => {
    expect(formatPoint(1.23456, 0.5)).toBe('(1.235, 0.5) m');
    expect(formatPoint(0.04, 0.01)).toBe('(4, 1) cm');
    expect(formatPoint(0, 0)).toBe('(0, 0) m');
  });

  it('cuts every number in JSON and leaves the rest', () => {
    const value = { to: { x: 1.23456, y: 2 }, name: 'a', list: [0.000123456, true, null] };
    expect(compactJson(value)).toBe(
      '{"to":{"x":1.235,"y":2},"name":"a","list":[0.0001235,true,null]}',
    );
    expect(exactJson(value)).toContain('1.23456');
  });
});
