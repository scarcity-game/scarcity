import { LogEntryItem, type EntryView } from './LogEntryItem.tsx';
import type { LogTree } from './logTree.ts';

interface LogTreeItemProps {
  tree: LogTree;
  expanded: boolean;
  /** Expands or collapses the tree with this root. */
  onExpand: (root: number, expanded: boolean) => void;
  view: EntryView;
}

function Toggle({ tree, expanded, onExpand }: Omit<LogTreeItemProps, 'view'>) {
  return (
    <button
      type="button"
      className="log-toggle"
      aria-expanded={expanded}
      aria-label={`Toggle tree ${tree.root}`}
      onClick={() => onExpand(tree.root, !expanded)}
    >
      {expanded ? '▾' : '▸'}
    </button>
  );
}

export function LogTreeItem({ tree, expanded, onExpand, view }: LogTreeItemProps) {
  const first = tree.nodes[0];
  if (first === undefined) return null;
  return (
    <li className="log-tree" data-root={tree.root}>
      {tree.entryCount > 1 && <Toggle tree={tree} expanded={expanded} onExpand={onExpand} />}
      {expanded ? (
        <ul className="log-nodes">
          {tree.nodes.map((node) => (
            <LogEntryItem key={node.entry.seq} node={node} view={view} />
          ))}
        </ul>
      ) : (
        <ul className="log-nodes">
          <LogEntryItem node={{ entry: first.entry, children: [] }} view={view} />
          <li className="log-more">
            <button
              type="button"
              className="log-more-button"
              onClick={() => onExpand(tree.root, true)}
            >
              (+{tree.entryCount - 1} more)
            </button>
          </li>
        </ul>
      )}
    </li>
  );
}
