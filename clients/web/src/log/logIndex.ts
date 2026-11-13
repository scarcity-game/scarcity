import { buildTrees, type LogTree, type LogNode } from './logTree.ts';
import type { LogEntry } from './types.ts';

export interface LogIndex {
  trees: LogTree[];
  treeOfSeq: Map<number, number>; /* seq -> index into trees of the tree that contains that entry (as a node at any depth) */
  names: Map<string, string>; /* entity id -> state.name seen in entries' changes[].state.name, later entries win; same logic as loggedNames in LogPanel.tsx */
}

export function buildLogIndex(entries: readonly LogEntry[]): LogIndex {
  // Build the trees first
  const trees = buildTrees(entries);

  // Create treeOfSeq mapping by walking each tree once
  const treeOfSeq = new Map<number, number>();

  // Walk each tree and populate treeOfSeq
  for (let treeIndex = 0; treeIndex < trees.length; treeIndex++) {
    const tree = trees[treeIndex];
    if (!tree) continue;
    // Process all nodes in the tree recursively
    const processNodes = (nodes: LogNode[]) => {
      for (const node of nodes) {
        // Map this entry's seq to its tree index
        treeOfSeq.set(node.entry.seq, treeIndex);

        // Recursively process children
        processNodes(node.children);
      }
    };

    processNodes(tree.nodes);
  }

  // Build names map (same logic as loggedNames in LogPanel.tsx)
  const names = new Map<string, string>();
  for (const entry of entries) {
    for (const change of entry.changes ?? []) {
      const name = change.state?.name;
      if (typeof name === 'string') {
        names.set(change.id, name);
      }
    }
  }

  return {
    trees,
    treeOfSeq,
    names
  };
}