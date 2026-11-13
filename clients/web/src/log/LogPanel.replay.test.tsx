import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { throwEntries } from '../replay/throwFixture.ts';
import { LogPanel } from './LogPanel.tsx';

describe('LogPanel in replay mode', () => {
  it('marks the current entry, expands its tree and scrolls to it (without using scrollIntoView)', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { container, rerender } = render(
      <LogPanel entries={throwEntries} resources={new Map()} currentSeq={12} />,
    );
    // Seq 12 is in a routine tree that is collapsed by default; the current entry opens it.
    const current = container.querySelector('[aria-current="true"]');
    expect(current).toHaveAttribute('data-seq', '12');
    expect(current).toHaveClass('log-current');
    // Verify scrollIntoView was NOT called (we no longer use it)
    expect(scrollIntoView).not.toHaveBeenCalled();
    rerender(<LogPanel entries={throwEntries} resources={new Map()} currentSeq={17} />);
    expect(container.querySelector('[aria-current="true"]')).toHaveAttribute('data-seq', '17');
    expect(container.querySelectorAll('[aria-current="true"]')).toHaveLength(1);
  });

  it('calls onSelect on click and on Enter or Space, only for the entry itself', () => {
    const onSelect = vi.fn();
    const { container } = render(
      <LogPanel entries={throwEntries} resources={new Map()} currentSeq={null} onSelect={onSelect} />,
    );
    // A nested entry: the click goes to the innermost entry only.
    fireEvent.click(container.querySelector('.log-entry[data-seq="16"]')!);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenLastCalledWith(16);
    fireEvent.keyDown(container.querySelector('.log-entry[data-seq="15"]')!, { key: 'Enter' });
    expect(onSelect).toHaveBeenLastCalledWith(15);
    fireEvent.keyDown(container.querySelector('.log-entry[data-seq="14"]')!, { key: ' ' });
    expect(onSelect).toHaveBeenLastCalledWith(14);
    expect(onSelect).toHaveBeenCalledTimes(3);
  });
});
