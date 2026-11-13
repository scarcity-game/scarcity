import { describe, expect, it } from 'vitest';
import { buildTrees, type LogNode } from './logTree.ts';
import type { LogEntry } from './types.ts';

const e = (
  seq: number,
  kind: string,
  cause: number | null,
  root: number,
  actionType?: string,
): LogEntry => ({
  seq,
  area: 'area:a',
  time: seq,
  kind,
  cause,
  root,
  resource: null,
  draws: [],
  changes: [],
  ...(actionType ? { action: { type: actionType } } : {}),
  ...(kind === 'effect' ? { effect: { type: 'damage' } } : {}),
});

const seqsOf = (nodes: LogNode[]) => nodes.map((n) => n.entry.seq);

describe('buildTrees', () => {
  it('nests a throw tree by cause', () => {
    const trees = buildTrees([
      e(1, 'action-start', null, 1, 'throw'),
      e(2, 'effect', 1, 1),
      e(3, 'effect', 2, 1),
      e(4, 'action-start', 3, 1, 'fly'),
      e(5, 'action-end', 4, 1, 'fly'),
      e(6, 'effect', 5, 1),
    ]);
    expect(trees).toHaveLength(1);
    const tree = trees[0]!;
    expect(tree.root).toBe(1);
    expect(tree.entryCount).toBe(6);
    expect(tree.routine).toBe(false);
    expect(seqsOf(tree.nodes)).toEqual([1]);
    let node = tree.nodes[0]!;
    for (const seq of [2, 3, 4, 5, 6]) {
      expect(seqsOf(node.children)).toEqual([seq]);
      node = node.children[0]!;
    }
  });

  it('makes one tree per root, in order, and marks single actions routine', () => {
    const trees = buildTrees([
      e(1, 'action-start', null, 1, 'orbit-step'),
      e(2, 'action-end', 1, 1, 'orbit-step'),
      e(3, 'action-start', null, 3, 'wait'),
      e(4, 'action-end', 3, 3, 'wait'),
    ]);
    expect(trees.map((t) => t.root)).toEqual([1, 3]);
    expect(trees.map((t) => t.routine)).toEqual([true, true]);
  });

  it('puts an entry whose cause is not loaded at the top of its root group', () => {
    const trees = buildTrees([e(10, 'effect', 9, 8), e(11, 'effect', 10, 8)]);
    expect(trees).toHaveLength(1);
    expect(trees[0]!.root).toBe(8);
    expect(seqsOf(trees[0]!.nodes)).toEqual([10]);
    expect(seqsOf(trees[0]!.nodes[0]!.children)).toEqual([11]);
  });

  it('keeps an orphan next to the root in its group', () => {
    const trees = buildTrees([e(1, 'action-start', null, 1, 'throw'), e(5, 'effect', 4, 1)]);
    expect(seqsOf(trees[0]!.nodes)).toEqual([1, 5]);
  });

  it('attaches an action-end that arrives in a later poll to its start', () => {
    const start = e(1, 'action-start', null, 1, 'orbit-step');
    const end = e(2, 'action-end', 1, 1, 'orbit-step');
    expect(buildTrees([start])[0]!.nodes[0]!.children).toHaveLength(0);
    const later = buildTrees([start, end]);
    expect(seqsOf(later[0]!.nodes)).toEqual([1]);
    expect(seqsOf(later[0]!.nodes[0]!.children)).toEqual([2]);
  });

  it('is not routine with an effect, a snapshot or another action type; a running wait is', () => {
    expect(
      buildTrees([e(1, 'action-start', null, 1, 'orbit-step'), e(2, 'effect', 1, 1)])[0]!.routine,
    ).toBe(false);
    expect(buildTrees([e(1, 'action-start', null, 1, 'wait')])[0]!.routine).toBe(true);
    expect(buildTrees([e(1, 'snapshot', null, 1)])[0]!.routine).toBe(false);
    expect(buildTrees([e(1, 'action-start', null, 1, 'throw')])[0]!.routine).toBe(false);
  });
});
