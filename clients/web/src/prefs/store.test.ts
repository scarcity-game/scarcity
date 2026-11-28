import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  booleanPreference,
  readPreference,
  usePreference,
  writePreference,
  type Preference,
} from './store.ts';

const flag = booleanPreference('test.flag.v1');

describe('preferences store', () => {
  it('reads the fallback when nothing, or nothing usable, is stored', () => {
    expect(readPreference(flag)).toBe(false);
    for (const stored of ['yes', '1', '"true"', '{']) {
      window.localStorage.setItem(flag.storageKey, stored);
      expect(readPreference(flag)).toBe(false);
    }
  });

  it('stores a value as JSON, and the fallback as nothing', () => {
    writePreference(flag, true);
    expect(window.localStorage.getItem(flag.storageKey)).toBe('true');
    expect(readPreference(flag)).toBe(true);
    writePreference(flag, false);
    expect(window.localStorage.getItem(flag.storageKey)).toBeNull();
  });

  it('re-renders a view when the value is set here or in another tab', () => {
    const { result } = renderHook(() => usePreference(flag));
    act(() => result.current[1](true));
    expect(result.current[0]).toBe(true);
    window.localStorage.removeItem(flag.storageKey);
    act(() => window.dispatchEvent(new StorageEvent('storage', { key: flag.storageKey })));
    expect(result.current[0]).toBe(false);
  });

  it('gives the same object until the stored text changes', () => {
    const point: Preference<{ x: number }> = {
      storageKey: 'test.point.v1',
      fallback: { x: 0 },
      parse: (stored) =>
        typeof stored === 'object' && stored !== null ? (stored as { x: number }) : undefined,
    };
    writePreference(point, { x: 1 });
    const first = readPreference(point);
    expect(readPreference(point)).toBe(first);
    writePreference(point, { x: 2 });
    expect(readPreference(point)).toEqual({ x: 2 });
  });
});
