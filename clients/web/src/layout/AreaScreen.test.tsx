import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AreaScreen } from './AreaScreen.tsx';
import { STORAGE_KEY } from './layoutStore.ts';
import { NARROW_QUERY } from './useNarrow.ts';

function renderScreen() {
  return render(
    <AreaScreen header={<h1>Header</h1>} map={<p>Map</p>} side={<p>Side</p>} log={<p>Log</p>} />,
  );
}

function stubMatchMedia(narrow: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: narrow && query === NARROW_QUERY,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

function expectAllRegions() {
  for (const text of ['Header', 'Map', 'Side', 'Log']) expect(screen.getByText(text)).toBeVisible();
}

const side = () => screen.getByRole('separator', { name: 'Resize inspector' });
const footer = () => screen.getByRole('separator', { name: 'Resize log' });

describe('AreaScreen, wide', () => {
  it('has the header, the map, the side and the log, split by two separators', () => {
    const { container } = renderScreen();
    expect(container.querySelector('.area-screen')).toHaveAttribute('data-layout', 'split');
    expectAllRegions();
    expect(side()).toHaveAttribute('aria-valuenow', '320');
    expect(footer()).toHaveAttribute('aria-valuenow', '220');
  });

  it('resizes with the keyboard and remembers the size', () => {
    renderScreen();
    fireEvent.keyDown(side(), { key: 'ArrowLeft' });
    fireEvent.keyDown(footer(), { key: 'ArrowUp' });
    fireEvent.keyDown(footer(), { key: 'ArrowUp' });
    expect(side()).toHaveAttribute('aria-valuenow', '336');
    const saved: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? 'null');
    expect(saved).toEqual({ side: 336, log: 252 });
  });

  it('resizes by dragging toward the map and starts from the saved size next time', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"side":300,"log":200}');
    const first = renderScreen();
    fireEvent.pointerDown(side(), { pointerId: 1, clientX: 500, clientY: 0 });
    fireEvent.pointerMove(side(), { pointerId: 1, clientX: 450, clientY: 0 });
    fireEvent.pointerUp(side(), { pointerId: 1, clientX: 450, clientY: 0 });
    expect(side()).toHaveAttribute('aria-valuenow', '350');
    first.unmount();
    renderScreen();
    expect(side()).toHaveAttribute('aria-valuenow', '350');
  });

  it('keeps sizes within the minimum', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"side":130,"log":200}');
    renderScreen();
    fireEvent.keyDown(side(), { key: 'ArrowRight' });
    expect(side()).toHaveAttribute('aria-valuenow', '120');
  });

  it('resets the layout', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"side":500,"log":400}');
    renderScreen();
    expect(side()).toHaveAttribute('aria-valuenow', '500');
    fireEvent.click(screen.getByRole('button', { name: 'Reset layout' }));
    expect(side()).toHaveAttribute('aria-valuenow', '320');
    expect(footer()).toHaveAttribute('aria-valuenow', '220');
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('AreaScreen, narrow', () => {
  it('stacks the regions, with no splitters and no reset', () => {
    stubMatchMedia(true);
    const { container } = renderScreen();
    expect(container.querySelector('.area-screen')).toHaveAttribute('data-layout', 'stacked');
    expect(screen.queryByRole('separator')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Reset layout' })).toBeNull();
    expectAllRegions();
  });

  it('is wide when the query does not match', () => {
    stubMatchMedia(false);
    renderScreen();
    expect(side()).toBeInTheDocument();
  });
});
