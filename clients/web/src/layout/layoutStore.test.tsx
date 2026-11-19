import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_SIZES, STORAGE_KEY, loadSizes, useLayoutSizes } from './layoutStore.ts';

function blockStorage() {
  const blocked = () => {
    throw new Error('blocked');
  };
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked);
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked);
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked);
}

describe('layout sizes', () => {
  it('defaults when nothing is saved', () => {
    expect(loadSizes()).toEqual(DEFAULT_SIZES);
  });

  it('saves a change and reads it back in a new session', () => {
    const first = renderHook(() => useLayoutSizes());
    act(() => first.result.current.update({ side: 400 }));
    const saved: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null');
    expect(saved).toEqual({ side: 400, log: 220 });
    first.unmount();
    expect(renderHook(() => useLayoutSizes()).result.current.sizes.side).toBe(400);
  });

  it('reset restores the defaults and forgets what was saved', () => {
    const { result } = renderHook(() => useLayoutSizes());
    act(() => result.current.update({ log: 500 }));
    act(() => result.current.reset());
    expect(result.current.sizes).toEqual(DEFAULT_SIZES);
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it.each(['not json', '{"side":"wide"}', '{"side":-5,"log":1e9}', 'null', '7'])(
    'falls back to the defaults for saved %s',
    (saved) => {
      window.localStorage.setItem(STORAGE_KEY, saved);
      expect(loadSizes()).toEqual(DEFAULT_SIZES);
    },
  );

  it('keeps a valid half of a partly valid value', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"side":450,"log":"x"}');
    expect(loadSizes()).toEqual({ side: 450, log: DEFAULT_SIZES.log });
  });

  it('works when storage throws', () => {
    blockStorage();
    const { result } = renderHook(() => useLayoutSizes());
    expect(result.current.sizes).toEqual(DEFAULT_SIZES);
    act(() => result.current.update({ side: 300 }));
    expect(result.current.sizes.side).toBe(300);
    act(() => result.current.reset());
    expect(result.current.sizes).toEqual(DEFAULT_SIZES);
  });
});
