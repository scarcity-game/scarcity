import { describe, expect, it } from 'vitest';
import type { LogEntry } from '../log/types.ts';
import { departedTo } from './useFollowing.ts';

const AREA = 'area:00000000-0000-4000-8000-000000000012';
const BELOW = 'area:00000000-0000-4000-8000-000000000013';
const DIVER = 'entity:00000000-0000-4000-8000-000000000015';

const entry = (seq: number, fields: Partial<LogEntry>): LogEntry => ({
  seq,
  area: AREA,
  time: seq,
  kind: 'effect',
  cause: null,
  root: seq,
  resource: null,
  draws: [],
  changes: [],
  ...fields,
});
const depart = (seq: number, toArea: string) =>
  entry(seq, {
    loggedEffect: { type: 'depart', params: { entity: DIVER } },
    result: { toStairs: 'entity:x', toArea, made: false },
    changes: [{ id: DIVER, type: 'entity', op: 'delete' }],
  });
const moved = (seq: number) =>
  entry(seq, {
    kind: 'action-end',
    resource: DIVER,
    changes: [{ id: DIVER, type: 'entity', op: 'update' }],
  });

describe('departedTo', () => {
  it('is the toArea of the departure, the newest entry that touches the entity', () => {
    expect(departedTo([moved(1), depart(2, BELOW), entry(3, {})], DIVER)).toBe(BELOW);
  });

  it('is undefined while the departure has not arrived', () => {
    expect(departedTo([moved(1)], DIVER)).toBeUndefined();
  });

  it('ignores an older departure the entity came back from, whatever order entries load in', () => {
    expect(departedTo([moved(5), depart(2, BELOW)], DIVER)).toBeUndefined();
  });

  it('ignores other entities leaving', () => {
    expect(
      departedTo([depart(2, BELOW)], 'entity:00000000-0000-4000-8000-000000000016'),
    ).toBeUndefined();
  });
});
