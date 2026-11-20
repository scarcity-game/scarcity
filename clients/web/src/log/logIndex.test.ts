import { describe, expect, it } from 'vitest';
import { buildLogIndex } from './logIndex.ts';
import type { LogEntry } from './types.ts';

const withAction = (entry: LogEntry, type: string): LogEntry => ({
  ...entry,
  actionInProgress: { type },
});

const e = (
  seq: number,
  kind: string,
  cause: number | null,
  root: number,
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
  ...(kind === 'effect' ? { loggedEffect: { type: 'damage' } } : {}),
});

describe('buildLogIndex', () => {
  it('handles empty input', () => {
    const index = buildLogIndex([]);
    expect(index.rows).toHaveLength(0);
    expect(index.rowOfSeq.size).toBe(0);
    expect(index.names.size).toBe(0);
  });

  it('is flat: one row per entry in seq order, whatever caused it', () => {
    const entries = [
      e(4, 'action-end', 3, 2),
      withAction(e(1, 'action-start', null, 1), 'throw'),
      e(2, 'effect', 1, 1),
      withAction(e(3, 'action-start', null, 2), 'wait'),
    ];

    const index = buildLogIndex(entries);

    expect(index.rows.map((r) => r.seq)).toEqual([1, 2, 3, 4]);
    expect([1, 2, 3, 4].map((seq) => index.rowOfSeq.get(seq))).toEqual([0, 1, 2, 3]);
  });

  it('collects names from changes state.name', () => {
    const entries = [
      withAction(e(1, 'action-start', null, 1), 'throw'),
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

    expect(index.rows).toHaveLength(12000);
    expect(index.rowOfSeq.get(12000)).toBe(11999); // 0-indexed
  });
});