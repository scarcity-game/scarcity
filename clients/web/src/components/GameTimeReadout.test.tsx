import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { GameTimeReadout } from './GameTimeReadout.tsx';

const text = () => screen.getByRole('timer').textContent;

describe('GameTimeReadout', () => {
  it('shows the area time to two decimals', () => {
    render(<GameTimeReadout time={1042.0} />);
    expect(text()).toBe('game time 1042.00');
  });
  it('jumps to the new time with each update', () => {
    const { rerender } = render(<GameTimeReadout time={1042.0} />);
    rerender(<GameTimeReadout time={1043.5} />);
    expect(text()).toBe('game time 1043.50');
    rerender(<GameTimeReadout time={1100} />);
    expect(text()).toBe('game time 1100.00');
  });
  it('shows time 0 as 0.00', () => {
    render(<GameTimeReadout time={0} />);
    expect(text()).toBe('game time 0.00');
  });
  it.each([undefined, null, 'x', NaN, Infinity])('shows a placeholder for %j', (time) => {
    render(<GameTimeReadout time={time} />);
    expect(text()).toBe('game time —');
  });
  it('does not move with wall time: no timer, same text a second later', () => {
    vi.useFakeTimers();
    try {
      render(<GameTimeReadout time={1042.0} />);
      expect(vi.getTimerCount()).toBe(0);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(text()).toBe('game time 1042.00');
    } finally {
      vi.useRealTimers();
    }
  });
});
