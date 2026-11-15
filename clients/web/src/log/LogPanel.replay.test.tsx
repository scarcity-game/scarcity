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
    rerender(<LogPanel entries={throwEntries} resources={new Map()} currentSeq={18} />);
    expect(container.querySelector('[aria-current="true"]')).toHaveAttribute('data-seq', '18');
    expect(container.querySelectorAll('[aria-current="true"]')).toHaveLength(1);
  });

  it('calls onSelect on click and on Enter or Space, only for the entry itself', () => {
    const onSelect = vi.fn();
    const { container } = render(
      <LogPanel entries={throwEntries} resources={new Map()} currentSeq={null} onSelect={onSelect} />,
    );
    // A nested entry: the click goes to the innermost entry only.
    fireEvent.click(container.querySelector('.log-entry[data-seq="17"]')!);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenLastCalledWith(17);
    fireEvent.keyDown(container.querySelector('.log-entry[data-seq="16"]')!, { key: 'Enter' });
    expect(onSelect).toHaveBeenLastCalledWith(16);
    fireEvent.keyDown(container.querySelector('.log-entry[data-seq="15"]')!, { key: ' ' });
    expect(onSelect).toHaveBeenLastCalledWith(15);
    expect(onSelect).toHaveBeenCalledTimes(3);
  });

  it('shows the intent that starts the throw tree, with its reported draw, and selects it', () => {
    const onSelect = vi.fn();
    const { container } = render(
      <LogPanel entries={throwEntries} resources={new Map()} currentSeq={null} onSelect={onSelect} />,
    );
    const intent = container.querySelector('.log-entry[data-seq="13"]')!;
    expect(intent).toHaveAttribute('data-kind', 'intent');
    expect(intent.querySelector(':scope > .log-what')).toHaveTextContent('orbital chose throw (reported: p = 0.25, u = 0.61)');
    expect(intent.querySelector(':scope > .log-draws')).toHaveTextContent('reported: categorical');
    fireEvent.click(intent);
    expect(onSelect).toHaveBeenLastCalledWith(13);
  });
});
