import { useLayoutEffect, useMemo, useRef, useState, type UIEvent } from 'react';
import type { Resource } from '../resources/cache.ts';
import { describe, drawText, entityOf, formatLogTime, shortId } from './format.ts';
import { buildTrees, type LogNode, type LogTree } from './logTree.ts';
import type { LogEntry } from './types.ts';

interface LogPanelProps {
  entries: readonly LogEntry[];
  resources: ReadonlyMap<string, Resource>;
  /** Called with the entity an entry is about while it is hovered or focused, null otherwise. */
  onHighlight?: (entityId: string | null) => void;
}

const STICK_SLOP_PX = 8;

export function LogPanel({ entries, resources, onHighlight }: LogPanelProps) {
  const trees = useMemo(() => buildTrees(entries), [entries]);
  // Names carried by the log itself, for resources the cache doesn't hold (any more).
  const loggedNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const entry of entries) {
      for (const change of entry.changes ?? []) {
        const name = change.state?.name;
        if (typeof name === 'string') names.set(change.id, name);
      }
    }
    return names;
  }, [entries]);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  const scrollRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  }, [entries, expanded]);
  const onScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    stickRef.current = el.scrollTop + el.clientHeight >= el.scrollHeight - STICK_SLOP_PX;
  };

  const nameOf = (id: string): string => {
    const cached = resources.get(id)?.state.name;
    if (typeof cached === 'string') return cached;
    return loggedNames.get(id) ?? shortId(id);
  };

  const renderNode = (node: LogNode) => {
    const { entry } = node;
    const entityId = entityOf(entry);
    return (
      <li
        key={entry.seq}
        className="log-entry"
        data-seq={entry.seq}
        data-kind={entry.kind}
        tabIndex={0}
        // Entries nest, and these events bubble: the innermost entry handles them.
        onMouseOver={(e) => {
          e.stopPropagation();
          onHighlight?.(entityId);
        }}
        onMouseOut={(e) => {
          e.stopPropagation();
          onHighlight?.(null);
        }}
        onFocus={(e) => {
          e.stopPropagation();
          onHighlight?.(entityId);
        }}
        onBlur={(e) => {
          e.stopPropagation();
          onHighlight?.(null);
        }}
      >
        <span className="log-time">{formatLogTime(entry.time)}</span>{' '}
        <span className="log-kind">{entry.kind}</span>{' '}
        {entityId !== null && (
          <>
            <span className="log-name">{nameOf(entityId)}</span>{' '}
          </>
        )}
        <span className="log-what">{describe(entry)}</span>
        {entry.draws.length > 0 && (
          <ul className="log-draws">
            {entry.draws.map((draw, i) => (
              <li key={i} className="log-draw">
                {drawText(draw)}
              </li>
            ))}
          </ul>
        )}
        {node.children.length > 0 && (
          <ul className="log-children">{node.children.map(renderNode)}</ul>
        )}
      </li>
    );
  };

  const renderTree = (tree: LogTree) => {
    const isExpanded = expanded[tree.root] ?? !tree.routine;
    const first = tree.nodes[0];
    if (first === undefined) return null;
    return (
      <li key={tree.root} className="log-tree" data-root={tree.root}>
        {tree.entryCount > 1 && (
          <button
            type="button"
            className="log-toggle"
            aria-expanded={isExpanded}
            aria-label={`Toggle tree ${tree.root}`}
            onClick={() => setExpanded((prev) => ({ ...prev, [tree.root]: !isExpanded }))}
          >
            {isExpanded ? '▾' : '▸'}
          </button>
        )}
        {isExpanded ? (
          <ul className="log-nodes">{tree.nodes.map(renderNode)}</ul>
        ) : (
          <ul className="log-nodes">
            {renderNode({ entry: first.entry, children: [] })}
            <li className="log-more">(+{tree.entryCount - 1} more)</li>
          </ul>
        )}
      </li>
    );
  };

  return (
    <section className="log-panel" aria-label="Log">
      <div className="log-scroll" ref={scrollRef} onScroll={onScroll}>
        {trees.length === 0 ? (
          <p className="log-empty">No log entries yet.</p>
        ) : (
          <ul className="log-trees">{trees.map(renderTree)}</ul>
        )}
      </div>
    </section>
  );
}
