import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PREFS } from './catalogue.ts';
import { PreferencesButton } from './PreferencesDialog.tsx';
import { readPreference } from './store.ts';

const open = () => fireEvent.click(screen.getByRole('button', { name: 'Preferences' }));
const dialog = () => screen.queryByRole('dialog', { name: 'Preferences' });
const box = (name: string) => screen.getByRole('checkbox', { name });

describe('PreferencesDialog', () => {
  it('opens from the button with every option off by default, focus on Close', () => {
    render(<PreferencesButton />);
    expect(dialog()).toBeNull();
    open();
    expect(dialog()).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(box('Skip animations when behind')).not.toBeChecked();
    expect(box('Show floor cells')).not.toBeChecked();
    expect(screen.getByRole('group', { name: 'Animation' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Map' })).toBeInTheDocument();
  });

  it('applies and remembers a choice, and shows it next time', () => {
    const { unmount } = render(<PreferencesButton />);
    open();
    fireEvent.click(box('Show floor cells'));
    expect(readPreference(PREFS.showFloorCells)).toBe(true);
    fireEvent.click(box('Skip animations when behind'));
    expect(window.localStorage.getItem(PREFS.skipBacklog.storageKey)).toBe('true');
    unmount();
    render(<PreferencesButton />);
    open();
    expect(box('Show floor cells')).toBeChecked();
    expect(box('Skip animations when behind')).toBeChecked();
  });

  it('closes with Escape, the Close button or a click outside, and gives the focus back', () => {
    const { container } = render(<PreferencesButton />);
    const button = screen.getByRole('button', { name: 'Preferences' });
    open();
    fireEvent.keyDown(box('Show floor cells'), { key: 'Escape' });
    expect(dialog()).toBeNull();
    expect(button).toHaveFocus();
    open();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(dialog()).toBeNull();
    open();
    fireEvent.click(dialog()!);
    expect(dialog()).toBeInTheDocument();
    fireEvent.click(container.querySelector('.pref-backdrop')!);
    expect(dialog()).toBeNull();
  });

  it('describes each option with its hint', () => {
    render(<PreferencesButton />);
    open();
    expect(box('Show floor cells')).toHaveAccessibleDescription(
      'Outline every floor cell under the walls; click one to inspect it.',
    );
  });
});
