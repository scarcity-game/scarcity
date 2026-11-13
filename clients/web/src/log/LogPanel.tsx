import { useLayoutEffect, useMemo, useRef, useState, type UIEvent } from 'react';
import type { Resource } from '../resources/cache.ts';
import { describe, drawText, entityOf, formatLogTime, shortId } from './format.ts';
import { buildLogIndex } from './logIndex.ts';
import { type LogNode, type LogTree } from './logTree.ts';
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

const STICK_SLOP_PX = 8;
const WINDOW_TREES = 60;
const OVERSCAN_TREES = 15;
const EST_TREE_PX = 24;

export function LogPanel({ entries, resources, onHighlight, currentSeq = null, onSelect }: LogPanelProps) {
  const index = useMemo(() => buildLogIndex(entries), [entries]);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  const [scrollTop, setScrollTop] = useState(0);
  const avgRef = useRef(EST_TREE_PX);
  const stickRef = useRef(true);
  const pendingNudgeRef = useRef(false);

  // Compute the current root for tree expansion logic
  const ti0 = currentSeq === null ? undefined : index.treeOfSeq.get(currentSeq);
  const currentRoot = ti0 === undefined ? null : (index.trees[ti0]?.root ?? null);

  const scrollRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    if (currentSeq !== null) {
      // In replay mode, scroll to the current entry
      const ti0 = index.treeOfSeq.get(currentSeq);
      if (ti0 !== undefined) {
        // Compute window same way render does to determine if we need to recenter
        const maxStart = Math.max(0, index.trees.length - WINDOW_TREES);
        const start = (stickRef.current && currentSeq === null) ? maxStart : Math.floor(scrollTop / avgRef.current) - OVERSCAN_TREES;
        const end = Math.min(index.trees.length, start + WINDOW_TREES);

        // Only recentre if the current entry is not within the visible window
        if (ti0 < start || ti0 >= end) {
          el.scrollTop = Math.max(0, ti0 * avgRef.current - el.clientHeight / 2);
          setScrollTop(el.scrollTop);
        }
        pendingNudgeRef.current = true;
      }
    } else {
      // In live mode, maintain stick-to-bottom behavior
      if (stickRef.current) {
        el.scrollTop = el.scrollHeight;
      }
    }
  }, [currentSeq, entries, index]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el || !pendingNudgeRef.current) return;

    // After render, ensure the marked element is properly scrolled into view
    const marked = el.querySelector('[aria-current="true"]');
    if (marked) {
      const top = marked.getBoundingClientRect().top - el.getBoundingClientRect().top + el.scrollTop;
      const h = marked.getBoundingClientRect().height;

      if (top < el.scrollTop) {
        el.scrollTop = top;
      } else if (top + h > el.scrollTop + el.clientHeight) {
        el.scrollTop = top + h - el.clientHeight;
      }
    }

    // Update scroll position state if it changed
    if (el.scrollTop !== scrollTop) {
      setScrollTop(el.scrollTop);
    }

    pendingNudgeRef.current = false;
  });

  const onScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    stickRef.current = el.scrollTop + el.clientHeight >= el.scrollHeight - STICK_SLOP_PX;
    setScrollTop(el.scrollTop);
  };

  const nameOf = (id: string): string => {
    const cached = resources.get(id)?.state.name;
    if (typeof cached === 'string') return cached;
    return index.names.get(id) ?? shortId(id);
  };

  const renderNode = (node: LogNode) => {
    const { entry } = node;
    const entityId = entityOf(entry);
    return (
      <li
        key={entry.seq}
        className={entry.seq === currentSeq ? 'log-entry log-current' : 'log-entry'}
        data-seq={entry.seq}
        data-kind={entry.kind}
        tabIndex={0}
        aria-current={entry.seq === currentSeq ? 'true' : undefined}
        onClick={onSelect ? (e) => { e.stopPropagation(); onSelect(entry.seq); } : undefined}
        onKeyDown={onSelect ? (e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); e.stopPropagation(); onSelect(entry.seq); } } : undefined}
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
    const isExpanded = expanded[tree.root] ?? (!tree.routine || tree.root === currentRoot);
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
            <li className="log-more">
              <button
                type="button"
                className="log-more-button"
                onClick={() => setExpanded((prev) => ({ ...prev, [tree.root]: true }))}
              >
                (+{tree.entryCount - 1} more)
              </button>
            </li>
          </ul>
        )}
      </li>
    );
  };

  // Windowing calculations
  const n = index.trees.length;
  const maxStart = Math.max(0, n - WINDOW_TREES);
  let start = (stickRef.current && currentSeq === null) ? maxStart : Math.floor(scrollTop / avgRef.current) - OVERSCAN_TREES;
  let end = start + WINDOW_TREES;

  // Ensure start is clamped properly
  start = Math.max(0, Math.min(start, maxStart));
  end = Math.min(n, start + WINDOW_TREES);

  // If we have a current sequence that might be outside the normal window,
  // ensure it's included in the view
  if (currentSeq !== null && ti0 !== undefined) {
    if (ti0 < start) {
      start = Math.max(0, ti0 - OVERSCAN_TREES);
      end = Math.min(n, start + WINDOW_TREES);
    } else if (ti0 >= end) {
      end = Math.min(n, ti0 + 1 + OVERSCAN_TREES);
      start = Math.max(0, end - WINDOW_TREES);
    }
  }

  // Update average height after render
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && el.children.length > 0) {
      const ul = el.querySelector('.log-trees');
      if (ul && ul.children.length > 0) {
        const height = ul.scrollHeight;
        const count = end - start;
        if (count > 0 && height > 0) {
          avgRef.current = height / count;
        }
      }
    }
  });

  return (
    <section className="log-panel" aria-label="Log">
      <div className="log-scroll" ref={scrollRef} onScroll={onScroll}>
        {n === 0 ? (
          <p className="log-empty">No log entries yet.</p>
        ) : (
          <>
            <div aria-hidden="true" style={{ height: start * avgRef.current }} />
            <ul className="log-trees">
              {index.trees.slice(start, end).map(renderTree)}
            </ul>
            <div aria-hidden="true" style={{ height: (n - end) * avgRef.current }} />
          </>
        )}
      </div>
    </section>
  );
}