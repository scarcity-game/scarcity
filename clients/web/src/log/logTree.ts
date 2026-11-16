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

export function buildTrees(entries: readonly LogEntry[]): LogTree[] {
  // Sort entries by seq to process them in order
  const sortedEntries = [...entries].sort((a, b) => a.seq - b.seq)

  // Map to keep track of all nodes built so far
  const nodeMap = new Map<number, LogNode>()

  // Group trees by root
  const treeGroups = new Map<number, LogTree>()

  // Keep track of the order in which roots first appear
  const rootOrder: number[] = []

  for (const entry of sortedEntries) {
    // Create a node for this entry
    const node: LogNode = { entry, children: [] }

    // Add to node map
    nodeMap.set(entry.seq, node)

    // A tree is routine if all its entries are intents or action-start/end of orbit-step or wait actions
    const isRoutine = isRoutineEntry(entry)

    // Get or create the tree for this root
    let tree = treeGroups.get(entry.root)
    if (!tree) {
      tree = {
        root: entry.root,
        nodes: [],
        entryCount: 0,
        routine: true
      }
      treeGroups.set(entry.root, tree)

      // Keep track of root order (first appearance)
      rootOrder.push(entry.root)
    }

    // Update routine status for this tree
    if (!isRoutine) {
      tree.routine = false
    }

    // Increment entry count
    tree.entryCount++

    // Handle parent-child relationship
    if (entry.cause !== null && nodeMap.has(entry.cause)) {
      // If the cause is already loaded, add as child
      const parentNode = nodeMap.get(entry.cause)!
      parentNode.children.push(node)
    } else {
      // Otherwise, add to top-level nodes of this tree
      tree.nodes.push(node)
    }
  }

  // Convert map to array in order of root appearance
  const result: LogTree[] = []
  for (const root of rootOrder) {
    const tree = treeGroups.get(root)!
    result.push(tree)
  }

  return result
}