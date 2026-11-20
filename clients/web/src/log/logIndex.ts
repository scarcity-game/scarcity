import type { LogEntry } from './types.ts';

/**
 * The log as the panel shows it: flat, one row per entry in log order (seq),
 * each one thing that happened at one game time. What caused an entry is a
 * link on its row, not a place in a tree.
 */
export interface LogIndex {
  rows: LogEntry[];
  /** seq -> its row */
  rowOfSeq: Map<number, number>;
  /** entity id -> the latest `name` seen in the entries' changes */
  names: Map<string, string>;
}

export function buildLogIndex(entries: readonly LogEntry[]): LogIndex {
  const rows = [...entries].sort((a, b) => a.seq - b.seq);
  const rowOfSeq = new Map(rows.map((entry, row) => [entry.seq, row]));
  const names = new Map<string, string>();
  for (const entry of rows) {
    for (const change of entry.changes ?? []) {
      const name = change.state?.name;
      if (typeof name === 'string') names.set(change.id, name);
    }
  }
  return { rows, rowOfSeq, names };
}
