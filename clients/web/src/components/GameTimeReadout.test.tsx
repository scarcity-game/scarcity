import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { GameTimeReadout } from './GameTimeReadout.tsx';

const text = () => screen.getByRole('timer').textContent;

describe('GameTimeReadout', () => {
  it('shows the area time in a fitting unit, the exact seconds as the tooltip', () => {
    render(<GameTimeReadout time={2149} />);
    expect(text()).toBe('game time 35:49');
    expect(screen.getByRole('timer')).toHaveAttribute('title', '2149 s');
  });
  it('jumps to the new time with each update', () => {
    const { rerender } = render(<GameTimeReadout time={42} />);
    expect(text()).toBe('game time 42 s');
    rerender(<GameTimeReadout time={43.5} />);
    expect(text()).toBe('game time 43.5 s');
    rerender(<GameTimeReadout time={7200} />);
    expect(text()).toBe('game time 2:00:00');
  });
  it('shows time 0 as 0 s', () => {
    render(<GameTimeReadout time={0} />);
    expect(text()).toBe('game time 0 s');
  });
  it.each([undefined, null, 'x', NaN, Infinity])('shows a placeholder for %j', (time) => {
    render(<GameTimeReadout time={time} />);
    expect(text()).toBe('game time —');
    expect(screen.getByRole('timer')).not.toHaveAttribute('title');
  });
  it('does not move with wall time: no timer, same text a second later', () => {
    vi.useFakeTimers();
    try {
      render(<GameTimeReadout time={42} />);
      expect(vi.getTimerCount()).toBe(0);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(text()).toBe('game time 42 s');
    } finally {
      vi.useRealTimers();
    }
  });
});
