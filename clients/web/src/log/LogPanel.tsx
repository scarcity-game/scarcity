import { useMemo, useState } from 'react';
import type { Resource } from '../resources/cache.ts';
import { shortId } from './format.ts';
import { LogTreeItem } from './LogTreeItem.tsx';
import { type EntryView } from './LogEntryItem.tsx';
import { buildLogIndex, type LogIndex } from './logIndex.ts';
import { useAverageHeight, useLogScroll, type LogScroll } from './useLogScroll.ts';
import { visibleWindow, type TreeWindow } from './logWindow.ts';
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
}

function entityName(id: string, resources: ReadonlyMap<string, Resource>, index: LogIndex): string {
  const cached = resources.get(id)?.name;
  if (typeof cached === 'string') return cached;
  return index.names.get(id) ?? shortId(id);
}

interface LogTreesProps {
  index: LogIndex;
  scroll: LogScroll;
  window: TreeWindow;
  currentRoot: number | null;
  view: EntryView;
}

function LogTrees({ index, scroll, window, currentRoot, view }: LogTreesProps) {
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  const onExpand = (root: number, value: boolean) =>
    setExpanded((prev) => ({ ...prev, [root]: value }));
  const { start, end } = window;
  const avgPx = scroll.avgRef.current;
  return (
    <>
      <div aria-hidden="true" style={{ height: start * avgPx }} />
      <ul className="log-trees">
        {index.trees.slice(start, end).map((tree) => (
          <LogTreeItem
            key={tree.root}
            tree={tree}
            expanded={expanded[tree.root] ?? (!tree.routine || tree.root === currentRoot)}
            onExpand={onExpand}
            view={view}
          />
        ))}
      </ul>
      <div aria-hidden="true" style={{ height: (index.trees.length - end) * avgPx }} />
    </>
  );
}

// The tree holding the current entry stays expanded; the window of trees to render.
function useLogWindow(index: LogIndex, scroll: LogScroll, currentSeq: number | null) {
  const currentTree = currentSeq === null ? undefined : index.treeOfSeq.get(currentSeq);
  const currentRoot = currentTree === undefined ? null : (index.trees[currentTree]?.root ?? null);
  const window = visibleWindow({
    treeCount: index.trees.length,
    scrollTop: scroll.scrollTop,
    avgPx: scroll.avgRef.current,
    stick: scroll.stickRef.current,
    currentSeq,
    currentTree,
  });
  useAverageHeight(scroll, window.end - window.start);
  return { window, currentRoot };
}

export function LogPanel({
  entries,
  resources,
  onHighlight,
  currentSeq = null,
  onSelect,
}: LogPanelProps) {
  const index = useMemo(() => buildLogIndex(entries), [entries]);
  const scroll = useLogScroll(index, entries, currentSeq);
  const { window, currentRoot } = useLogWindow(index, scroll, currentSeq);
  const nameOf = (id: string) => entityName(id, resources, index);
  const view: EntryView = { currentSeq, nameOf, onHighlight, onSelect };

  return (
    <section className="log-panel" aria-label="Log">
      <div className="log-scroll" ref={scroll.scrollRef} onScroll={scroll.onScroll}>
        {index.trees.length === 0 ? (
          <p className="log-empty">No log entries yet.</p>
        ) : (
          <LogTrees
            index={index}
            scroll={scroll}
            window={window}
            currentRoot={currentRoot}
            view={view}
          />
        )}
      </div>
    </section>
  );
}
