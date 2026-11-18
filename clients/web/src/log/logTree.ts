import type { LogEntry } from './types.ts';

export interface LogNode {
  entry: LogEntry
  children: LogNode[]
}

export interface LogTree {
  root: number
  nodes: LogNode[]
  entryCount: number
  routine: boolean
}

const ROUTINE_TYPES = new Set(['orbit-step', 'wait'])

function isRoutineEntry(entry: LogEntry): boolean {
  if (entry.kind === 'intent') return ROUTINE_TYPES.has(entry.actionSpec?.type ?? '')
  if (entry.kind !== 'action-start' && entry.kind !== 'action-end') return false
  return ROUTINE_TYPES.has(entry.actionInProgress?.type ?? '')
}

// The tree for this entry's root, created (empty, routine) the first time the root is seen.
function treeFor(treeGroups: Map<number, LogTree>, root: number): LogTree {
  let tree = treeGroups.get(root)
  if (!tree) {
    tree = { root, nodes: [], entryCount: 0, routine: true }
    treeGroups.set(root, tree)
  }
  return tree
}

export function buildTrees(entries: readonly LogEntry[]): LogTree[] {
  // Sort entries by seq to process them in order
  const sortedEntries = [...entries].sort((a, b) => a.seq - b.seq)

  // Map to keep track of all nodes built so far
  const nodeMap = new Map<number, LogNode>()

  // Group trees by root; a Map keeps the order in which roots first appear
  const treeGroups = new Map<number, LogTree>()

  for (const entry of sortedEntries) {
    const node: LogNode = { entry, children: [] }
    nodeMap.set(entry.seq, node)
    const tree = treeFor(treeGroups, entry.root)

    // A tree is routine if all its entries are intents or action-start/end of orbit-step or wait actions
    if (!isRoutineEntry(entry)) tree.routine = false
    tree.entryCount++

    // If the cause is already loaded, add as its child; otherwise as a top-level node of this tree
    const parentNode = entry.cause === null ? undefined : nodeMap.get(entry.cause)
    if (parentNode) parentNode.children.push(node)
    else tree.nodes.push(node)
  }

  return [...treeGroups.values()]
}
