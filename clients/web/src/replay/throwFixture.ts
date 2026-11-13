import type { LogEntry, LogChange } from '../log/types.ts';

export const AREA = 'area:00000000-0000-4000-8000-000000000002';
export const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
export const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
export const PEBBLE = 'entity:00000000-0000-4000-8000-000000000005';
export const RUBBLE = 'entity:00000000-0000-4000-8000-000000000006';

export function entry(seq:number, time:number, kind:string, cause:number|null, root:number, extra: Partial<LogEntry>): LogEntry {
  return { seq, area: AREA, time, kind, cause, root, resource: null, draws: [], changes: [], ...extra };
}

export const throwSnapshot: LogEntry = entry(10, 1040, 'snapshot', null, 10, {
  changes: [
    { id: AREA, type: 'area', op: 'snapshot', version: 3, state: { name: 'Test room', width: 10, height: 8 } },
    { id: PILLAR, type: 'entity', op: 'snapshot', version: 1, state: { kind: 'fixed', name: 'pillar', position: { x: 5, y: 4 }, hp: 100 } },
    { id: ORBITER, type: 'entity', op: 'snapshot', version: 1, state: { kind: 'mobile', name: 'orbiter', position: { x: 2, y: 2 } } }
  ]
});

const ch = (id: string, version: number | null, state: Record<string, unknown>): LogChange => ({ id, type: 'entity', op: 'changed', version, state });
const created = (id: string, state: Record<string, unknown>): LogChange => ({ id, type: 'entity', op: 'created', parent: AREA, version: 1, state });
const destroyed = (id: string): LogChange => ({ id, type: 'entity', op: 'destroyed' });
const orb = (extra: Record<string, unknown> = {}) => ({ kind: 'mobile', name: 'orbiter', position: { x: 2, y: 2 }, ...extra });
const peb = (x: number, y: number, extra: Record<string, unknown> = {}) => ({ kind: 'mobile', name: 'pebble', position: { x, y }, ...extra });
const rub = (extra: Record<string, unknown> = {}) => ({ kind: 'fixed', name: 'rubble', position: { x: 5, y: 4 }, ...extra });

export const throwEntries: LogEntry[] = [
  entry(11, 1040.5, 'action-start', null, 11, { resource: ORBITER, action: { type: 'wait', seq: 11 }, changes: [ch(ORBITER, 2, orb({ action: { type: 'wait', seq: 11 } }))] }),
  entry(12, 1041, 'action-end', 11, 11, { resource: ORBITER, action: { type: 'wait', seq: 11 }, changes: [ch(ORBITER, 3, orb())] }),
  entry(13, 1041, 'action-start', null, 13, { resource: ORBITER, action: { type: 'throw', seq: 13 }, changes: [ch(ORBITER, 4, orb({ action: { type: 'throw', seq: 13 } }))] }),
  entry(14, 1041, 'effect', 13, 13, { effect: { type: 'create' }, changes: [created(PEBBLE, peb(2, 2))] }),
  entry(15, 1041, 'action-start', 14, 13, { resource: PEBBLE, action: { type: 'fly', seq: 15 }, changes: [ch(PEBBLE, 1, peb(2, 2, { action: { type: 'fly', seq: 15 } }))] }),
  entry(16, 1042, 'action-end', 15, 13, { resource: PEBBLE, action: { type: 'fly', seq: 15 }, changes: [ch(PEBBLE, 2, peb(5, 4))] }),
  entry(17, 1042, 'effect', 16, 13, { effect: { type: 'damage' }, changes: [ch(PILLAR, 2, { kind: 'fixed', name: 'pillar', position: { x: 5, y: 4 }, hp: 97.5 })] }),
  entry(18, 1042, 'effect', 16, 13, { effect: { type: 'break' } }),
  entry(19, 1042, 'effect', 18, 13, { effect: { type: 'destroy' }, changes: [destroyed(PEBBLE)] }),
  entry(20, 1042, 'effect', 18, 13, { effect: { type: 'create' }, changes: [created(RUBBLE, rub())] }),
  entry(21, 1042, 'action-start', 20, 13, { resource: RUBBLE, action: { type: 'decay', seq: 21 }, changes: [ch(RUBBLE, 1, rub({ action: { type: 'decay', seq: 21 } }))] }),
  entry(22, 1062, 'action-end', 21, 13, { resource: RUBBLE, action: { type: 'decay', seq: 21 }, changes: [ch(RUBBLE, 2, rub())] }),
  entry(23, 1062, 'effect', 22, 13, { effect: { type: 'destroy' }, changes: [destroyed(RUBBLE)] }),
  entry(24, 1063, 'action-start', null, 24, { resource: ORBITER, action: { type: 'wait', seq: 24 }, changes: [ch(ORBITER, 5, orb({ action: { type: 'wait', seq: 24 } }))] }),
];
