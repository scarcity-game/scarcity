import { useMemo } from 'react';
import type { Resource } from '../resources/cache.ts';
import { shortId } from './format.ts';
import { LogEntryItem, type EntryView } from './LogEntryItem.tsx';
import { buildLogIndex, type LogIndex } from './logIndex.ts';
import { useAverageHeight, useLogScroll, type LogScroll } from './useLogScroll.ts';
import { visibleWindow, type RowWindow } from './logWindow.ts';
import type { LogEntry } from './types.ts';

interface LogPanelProps {
  entries: readonly LogEntry[];
  resources: ReadonlyMap<string, Resource>;
  /** Called with the entity an entry is about while it is hovered or focused, null otherwise. */
  onHighlight?: (entityId: string | null) => void;
  /** The entry the replay is at; marked */
  currentSeq?: number | null;
  /** Called when an entry is clicked or activated with Enter or Space */
  onSelect?: (seq: number) => void;
  /** Called when an entity's name in an entry is clicked */
  onSelectEntity?: (entityId: string) => void;
}

function entityName(id: string, resources: ReadonlyMap<string, Resource>, index: LogIndex): string {
  const cached = resources.get(id)?.name;
  if (typeof cached === 'string') return cached;
  return index.names.get(id) ?? shortId(id);
}

interface LogRowsProps {
  index: LogIndex;
  scroll: LogScroll;
  window: RowWindow;
  view: EntryView;
}

function LogRows({ index, scroll, window, view }: LogRowsProps) {
  const { start, end } = window;
  const avgPx = scroll.avgRef.current;
  return (
    <>
      <div aria-hidden="true" style={{ height: start * avgPx }} />
      <ul className="log-rows">
        {index.rows.slice(start, end).map((entry) => (
          <LogEntryItem key={entry.seq} entry={entry} view={view} />
        ))}
      </ul>
      <div aria-hidden="true" style={{ height: (index.rows.length - end) * avgPx }} />
    </>
  );
}

// The window of rows to render; it always holds the current entry's row.
function useLogWindow(index: LogIndex, scroll: LogScroll, currentSeq: number | null): RowWindow {
  const window = visibleWindow({
    rowCount: index.rows.length,
    scrollTop: scroll.scrollTop,
    avgPx: scroll.avgRef.current,
    stick: scroll.stickRef.current,
    currentSeq,
    currentRow: currentSeq === null ? undefined : index.rowOfSeq.get(currentSeq),
  });
  useAverageHeight(scroll, window.end - window.start);
  return window;
}

function entryView(props: LogPanelProps, index: LogIndex): EntryView {
  const { resources, onHighlight, currentSeq = null, onSelect, onSelectEntity } = props;
  const nameOf = (id: string) => entityName(id, resources, index);
  const entryOf = (seq: number) => {
    const row = index.rowOfSeq.get(seq);
    return row === undefined ? undefined : index.rows[row];
  };
  return { currentSeq, nameOf, entryOf, onHighlight, onSelect, onSelectEntity };
}

export function LogPanel(props: LogPanelProps) {
  const { entries, currentSeq = null } = props;
  const index = useMemo(() => buildLogIndex(entries), [entries]);
  const scroll = useLogScroll(index, entries, currentSeq);
  const window = useLogWindow(index, scroll, currentSeq);
  const view = entryView(props, index);

  return (
    <section className="log-panel" aria-label="Log">
      <div className="log-scroll" ref={scroll.scrollRef} onScroll={scroll.onScroll}>
        {index.rows.length === 0 ? (
          <p className="log-empty">No log entries yet.</p>
        ) : (
          <LogRows index={index} scroll={scroll} window={window} view={view} />
        )}
      </div>
    </section>
  );
}
