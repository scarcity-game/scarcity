import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { PREFS } from './catalogue.ts';
import { MESSAGE_TYPES } from './messages.ts';
import { PreferencesLink, PreferencesPage } from './PreferencesPage.tsx';
import { readPreference } from './store.ts';

function renderApp(start = '/somewhere?x=1') {
  return render(
    <MemoryRouter initialEntries={[start]}>
      <PreferencesLink />
      <Routes>
        <Route path="/preferences" element={<PreferencesPage />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  );
}
const open = () => fireEvent.click(screen.getByRole('link', { name: 'Preferences' }));
const tab = (name: string) => screen.getByRole('tab', { name });

describe('PreferencesPage', () => {
  beforeEach(() => window.localStorage.clear());

  it('opens on General with every option off, and Back returns to where the link was clicked', () => {
    renderApp();
    open();
    expect(screen.getByRole('heading', { name: 'Preferences', level: 1 })).toBeInTheDocument();
    expect(tab('General')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('checkbox', { name: 'Skip animations when behind' })).not.toBeChecked();
    expect(screen.getByRole('group', { name: 'Map' })).toBeInTheDocument();
    fireEvent.click(tab('Messages'));
    fireEvent.click(screen.getByRole('link', { name: 'Back' }));
    expect(screen.getByText('elsewhere')).toBeInTheDocument();
  });

  it('Back goes to the start when the page was opened directly', () => {
    renderApp('/preferences');
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/');
  });

  it('remembers a General choice', () => {
    renderApp('/preferences');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show floor cells' }));
    expect(readPreference(PREFS.showFloorCells)).toBe(true);
    expect(window.localStorage.getItem(PREFS.showFloorCells.storageKey)).toBe('true');
  });

  it('switches tabs with the arrow keys, and keeps the tab in the URL', () => {
    renderApp('/preferences?tab=messages');
    expect(tab('Messages')).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(tab('Messages'), { key: 'ArrowLeft' });
    expect(tab('General')).toHaveAttribute('aria-selected', 'true');
    expect(tab('General')).toHaveFocus();
  });

  it('Messages: a minimum level per category, default 5, stored only when changed', () => {
    renderApp('/preferences?tab=messages');
    const game = within(screen.getByRole('group', { name: 'Game' }));
    const level = game.getByRole('combobox', { name: /Minimum level/ });
    expect(level).toHaveValue('5');
    fireEvent.change(level, { target: { value: '2' } });
    expect(window.localStorage.getItem('scarcity.minLevel.game.v1')).toBe('2');
    fireEvent.change(level, { target: { value: '5' } });
    expect(window.localStorage.getItem('scarcity.minLevel.game.v1')).toBeNull();
    expect(within(screen.getByRole('group', { name: 'OOC' })).getAllByRole('option')).toHaveLength(
      12,
    );
  });

  it('Messages: lists every type under its category with a sample', () => {
    renderApp('/preferences?tab=messages');
    for (const t of MESSAGE_TYPES) {
      expect(screen.getByTestId(`sample-${t.category}-${t.id}`)).toBeInTheDocument();
    }
    expect(
      within(screen.getByRole('group', { name: 'Server' })).getByText('System'),
    ).toBeInTheDocument();
  });

  it('Messages: a colour change shows in the sample, is stored, and Reset removes it', () => {
    renderApp('/preferences?tab=messages');
    const sample = screen.getByTestId('sample-game-death');
    const reset = screen.getByRole('button', { name: 'Reset Death colours' });
    expect(reset).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Death text colour'), { target: { value: '#00ff00' } });
    expect(sample).toHaveStyle({ color: '#00ff00' });
    const key = 'scarcity.messageColours.game.death.v1';
    expect(JSON.parse(window.localStorage.getItem(key)!)).toEqual({
      fg: '#00ff00',
      bg: '#991b1b',
    });
    fireEvent.click(reset);
    expect(window.localStorage.getItem(key)).toBeNull();
    expect(sample).toHaveStyle({ color: '#ffffff' });
  });

  it('Messages: choosing the default colours again stores nothing', () => {
    renderApp('/preferences?tab=messages');
    const fg = screen.getByLabelText('Death text colour');
    fireEvent.change(fg, { target: { value: '#00ff00' } });
    fireEvent.change(fg, { target: { value: '#ffffff' } });
    expect(window.localStorage.getItem('scarcity.messageColours.game.death.v1')).toBeNull();
  });
});
