import { describe, expect, it } from 'vitest';
import { buildLogIndex } from './logIndex.ts';
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
  time: seq / 10,
  kind,
  cause,
  root,
  resource: null,
  draws: [],
  changes: [],
  ...(actionType ? { action: { type: actionType } } : {}),
  ...(kind === 'effect' ? { effect: { type: 'damage' } } : {}),
});

describe('buildLogIndex', () => {
  it('handles empty input', () => {
    const index = buildLogIndex([]);
    expect(index.trees).toHaveLength(0);
    expect(index.treeOfSeq.size).toBe(0);
    expect(index.names.size).toBe(0);
  });

  it('creates correct tree mappings for two trees where a child entry maps to its tree index', () => {
    const entries = [
      e(1, 'action-start', null, 1, 'throw'),
      e(2, 'effect', 1, 1),
      e(3, 'action-start', null, 2, 'wait'),
      e(4, 'action-end', 3, 2),
    ];

    const index = buildLogIndex(entries);

    expect(index.trees).toHaveLength(2);
    expect(index.treeOfSeq.get(1)).toBe(0); // entry 1 belongs to tree 0
    expect(index.treeOfSeq.get(2)).toBe(0); // entry 2 belongs to tree 0
    expect(index.treeOfSeq.get(3)).toBe(1); // entry 3 belongs to tree 1
    expect(index.treeOfSeq.get(4)).toBe(1); // entry 4 belongs to tree 1
  });

  it('collects names from changes state.name', () => {
    const entries = [
      e(1, 'action-start', null, 1, 'throw'),
      e(2, 'effect', 1, 1),
    ];

    // Add changes with names to the entries
    entries[0]!.changes = [{ id: 'entity:1', type: 'update', op: 'set', state: { name: 'Player' } }];
    entries[1]!.changes = [{ id: 'entity:2', type: 'update', op: 'set', state: { name: 'Enemy' } }];

    const index = buildLogIndex(entries);

    expect(index.names.get('entity:1')).toBe('Player');
    expect(index.names.get('entity:2')).toBe('Enemy');
  });

  it('handles 12000 entries correctly', () => {
    const entries: LogEntry[] = [];
    for (let i = 1; i <= 12000; i++) {
      entries.push({
        seq: i,
        area: 'area:a',
        time: i / 10,
        kind: 'action-start',
        cause: null,
        root: i,
        resource: null,
        draws: [],
        changes: [],
      });
    }

    const index = buildLogIndex(entries);

    expect(index.trees).toHaveLength(12000);
    expect(index.treeOfSeq.get(12000)).toBe(11999); // 0-indexed
  });
});