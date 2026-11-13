import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { LogPanel } from './LogPanel.tsx';
import type { LogEntry } from './types.ts';

// Create a large set of log entries for testing windowing
const makeEntries = (n: number): LogEntry[] => {
  const entries: LogEntry[] = [];
  for (let i = 1; i <= n; i += 3) {
    // Create three entries per tree
    entries.push({
      seq: i,
      area: 'area:a',
      time: 1000 + i / 10,
      kind: 'action-start',
      cause: null,
      root: i,
      resource: null,
      action: { type: 'throw' },
      draws: [],
      changes: [],
    });
    entries.push({
      seq: i + 1,
      area: 'area:a',
      time: 1000 + (i + 1) / 10,
      kind: 'effect',
      cause: i,
      root: i,
      resource: null,
      draws: [],
      changes: [],
    });
    entries.push({
      seq: i + 2,
      area: 'area:a',
      time: 1000 + (i + 2) / 10,
      kind: 'action-end',
      cause: i,
      root: i,
      resource: null,
      action: { type: 'throw' },
      draws: [],
      changes: [],
    });
  }
  return entries;
};

// Build the large entry list once at module level
const LARGE_ENTRY_LIST = makeEntries(12000); // 4000 trees

describe('LogPanel windowing', () => {
  // Test 1: Live mode - should show a reasonable number of entries, with newest shown
  it('shows live mode with reasonable entry count and newest entry visible', () => {
    const { container } = render(<LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} />);
    const entries = container.querySelectorAll('.log-entry');
    expect(entries.length).toBeGreaterThan(0);
    expect(entries.length).toBeLessThanOrEqual(400);

    // The newest entry (12000) should be in the DOM
    const newestEntry = container.querySelector('[data-seq="12000"]');
    expect(newestEntry).toBeInTheDocument();

    // The oldest entry (1) should not be present
    const oldestEntry = container.querySelector('[data-seq="1"]');
    expect(oldestEntry).toBeNull();
  });

  // Test 2: Replay mode with currentSeq 6000
  it('shows replay mode with currentSeq 6000 and correct marked element', () => {
    const { container } = render(
      <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={6000} />
    );

    const entries = container.querySelectorAll('.log-entry');
    expect(entries.length).toBeLessThanOrEqual(400);

    // The element with data-seq 6000 should be marked
    const markedElement = container.querySelector('[data-seq="6000"]');
    expect(markedElement).toHaveAttribute('aria-current', 'true');
  });

  // Test 3: Performance test - rerendering with currentSeq changes should be fast
  it('rerenders quickly when changing currentSeq in replay mode', () => {
    const { rerender } = render(
      <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={6000} />
    );

    // Measure performance of multiple rerenders - budget is generous for CI machines
    const RERENDER_COUNT = 20;
    const BUDGET_MS = 150; // a step re-renders a window of 60 trees; the budget is generous for CI machines

    let totalTime = 0;
    for (let i = 0; i < RERENDER_COUNT; i++) {
      const start = performance.now();
      rerender(
        <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={6000 + i} />
      );
      const end = performance.now();
      totalTime += (end - start);
    }

    const averageTime = totalTime / RERENDER_COUNT;
    expect(averageTime).toBeLessThan(BUDGET_MS);
  });

  // Test 4: Scrolling brings more entries in
  it('loads more entries when scrolling', () => {
    const { container } = render(
      <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} />
    );

    // Get the scroll element and simulate scrolling to load more entries
    const scrollElement = container.querySelector('.log-scroll') as HTMLElement;

    // Mock scrollHeight and clientHeight to make sure we can scroll properly
    Object.defineProperty(scrollElement, 'scrollHeight', { configurable: true, value: 24 * 4000 });
    Object.defineProperty(scrollElement, 'clientHeight', { configurable: true, value: 400 });

    // Scroll to show entries from tree index 2000 (entry 6001)
    fireEvent.scroll(scrollElement, { target: { scrollTop: 24 * 2000 } });

    // Verify that entry 6001 is now present
    const entry6001 = container.querySelector('[data-seq="6001"]');
    expect(entry6001).toBeInTheDocument();

    // Verify entry count is still reasonable and newest is not shown
    const entries = container.querySelectorAll('.log-entry');
    expect(entries.length).toBeLessThanOrEqual(400);

    const newestEntry = container.querySelector('[data-seq="12000"]');
    expect(newestEntry).toBeNull();
  });

  // Test 5: The page does not scroll when in replay mode
  it('does not scroll the page in replay mode', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    const originalScrollY = window.scrollY;
    window.scrollTo = vi.fn();

    render(
      <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={100} />
    );

    // Rerender with different currentSeq values
    const { rerender } = render(
      <LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={5000} />
    );
    rerender(<LogPanel entries={LARGE_ENTRY_LIST} resources={new Map()} currentSeq={9000} />);

    // Verify scrollIntoView and window.scrollTo were never called
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(window.scrollTo).not.toHaveBeenCalled();

    // Verify window.scrollY is unchanged
    expect(window.scrollY).toBe(originalScrollY);
  });

  // Test 6: Clicking into a collapsed tree expands it
  it('expands collapsed trees when clicking the more button', () => {
    // Create a small test set with routine trees (action-start and action-end with orbit-step)
    const routineEntries = [
      // First routine tree
      {
        seq: 1,
        area: 'area:a',
        time: 1001,
        kind: 'action-start',
        cause: null,
        root: 1,
        resource: null,
        action: { type: 'orbit-step' },
        draws: [],
        changes: [],
      },
      {
        seq: 2,
        area: 'area:a',
        time: 1002,
        kind: 'action-end',
        cause: 1,
        root: 1,
        resource: null,
        action: { type: 'orbit-step' },
        draws: [],
        changes: [],
      },
    ];

    const onSelect = vi.fn();
    const { container } = render(
      <LogPanel
        entries={routineEntries}
        resources={new Map()}
        currentSeq={null}
        onSelect={onSelect}
      />
    );

    // Initially only the first entry should be visible (routine tree collapsed by default)
    const firstEntry = container.querySelector('[data-seq="1"]');
    expect(firstEntry).toBeInTheDocument();

    const secondEntry = container.querySelector('[data-seq="2"]');
    expect(secondEntry).toBeNull();

    // Check for the more button that should exist for routine trees
    const moreButton = container.querySelector('.log-more-button');
    if (moreButton) {
      fireEvent.click(moreButton);
    }

    // Now entry 2 should be visible
    const expandedSecondEntry = container.querySelector('[data-seq="2"]');
    expect(expandedSecondEntry).toBeInTheDocument();

    // Clicking the entry should call onSelect with seq 2
    if (expandedSecondEntry) {
      fireEvent.click(expandedSecondEntry);
      expect(onSelect).toHaveBeenCalledWith(2);
    }
  });
});