import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameTimeReadout } from './GameTimeReadout.tsx';

const T0 = 1794441600000;
const anchor = { gameTime: 1042.0, wallTime: T0, rate: 1.0 };

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});
afterEach(() => vi.useRealTimers());

const text = () => screen.getByRole('timer').textContent;

describe('GameTimeReadout', () => {
  it('shows the time and advances as time passes', () => {
    render(<GameTimeReadout clock={anchor} />);
    expect(text()).toBe('game time 1042.00 (rate 1.0)');
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(text()).toBe('game time 1043.00 (rate 1.0)');
  });
  it('takes a new anchor on the next refresh', () => {
    const { rerender } = render(<GameTimeReadout clock={anchor} />);
    rerender(<GameTimeReadout clock={{ gameTime: 5, wallTime: T0, rate: 2.5 }} />);
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(text()).toBe('game time 5.50 (rate 2.5)');
  });
  it.each([undefined, null, 'x', { gameTime: NaN, wallTime: 1, rate: 1 }])(
    'shows a placeholder for %j',
    (clock) => {
      render(<GameTimeReadout clock={clock} />);
      expect(text()).toBe('game time —');
    },
  );
  it('clears its timer on unmount', () => {
    const { unmount } = render(<GameTimeReadout clock={anchor} />);
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
