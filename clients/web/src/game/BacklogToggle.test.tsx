import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BacklogToggle } from './BacklogToggle.tsx';
import { STORAGE_KEY, skipBacklog } from './backlogPref.ts';

const box = () => screen.getByRole('checkbox', { name: 'Skip animations when behind' });

describe('BacklogToggle', () => {
  it('is off by default: every animation plays', () => {
    render(<BacklogToggle />);
    expect(box()).not.toBeChecked();
    expect(skipBacklog()).toBe(false);
  });

  it('remembers the choice in this browser, and forgets it when switched off', () => {
    const { unmount } = render(<BacklogToggle />);
    fireEvent.click(box());
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('true');
    expect(skipBacklog()).toBe(true);
    unmount();
    render(<BacklogToggle />);
    expect(box()).toBeChecked();
    fireEvent.click(box());
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(skipBacklog()).toBe(false);
  });

  it('reads anything but "true" as off', () => {
    window.localStorage.setItem(STORAGE_KEY, 'yes');
    render(<BacklogToggle />);
    expect(box()).not.toBeChecked();
  });
});
