import type { LogEntry, LogChange } from '../log/types.ts';

export const AREA = 'area:00000000-0000-4000-8000-000000000002';
export const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
export const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
export const PEBBLE = 'entity:00000000-0000-4000-8000-000000000005';
export const RUBBLE = 'entity:00000000-0000-4000-8000-000000000006';

export function entry(
  seq: number,
  time: number,
  kind: string,
  extra: Partial<LogEntry> & Pick<LogEntry, "cause" | "root">,
): LogEntry {
  return { seq, area: AREA, time, kind, resource: null, draws: [], changes: [], ...extra };
}

export const throwSnapshot: LogEntry = entry(10, 1040, 'snapshot', { cause: null, root: 10,
  changes: [
    { id: AREA, type: 'area', op: 'snapshot', version: 3, state: { name: 'Test room' } },
    { id: PILLAR, type: 'entity', op: 'snapshot', version: 1, state: { entityKind: 'fixed', prototype: 'pillar', name: 'pillar', position: { x: 5, y: 4 }, radius: 0.5, damageable: true, hp: 100 } },
    { id: ORBITER, type: 'entity', op: 'snapshot', version: 1, state: { entityKind: 'mob', prototype: 'orbiter', name: 'orbiter', position: { x: 2, y: 2 }, radius: 0.25 } }
  ]
});

const ch = (id: string, version: number | null, state: Record<string, unknown>): LogChange => ({ id, type: 'entity', op: 'changed', version, state });
const created = (id: string, state: Record<string, unknown>): LogChange => ({ id, type: 'entity', op: 'created', parent: AREA, version: 1, state });
const destroyed = (id: string): LogChange => ({ id, type: 'entity', op: 'destroyed' });
const orb = (extra: Record<string, unknown> = {}) => ({ entityKind: 'mob', prototype: 'orbiter', name: 'orbiter', position: { x: 2, y: 2 }, radius: 0.25, ...extra });
const peb = (x: number, y: number, extra: Record<string, unknown> = {}) => ({ entityKind: 'mob', prototype: 'pebble', name: 'pebble', position: { x, y }, radius: 0.015, ...extra });
const rub = (extra: Record<string, unknown> = {}) => ({ entityKind: 'fixed', prototype: 'rubble', name: 'rubble', position: { x: 5, y: 4 }, radius: 0.3, ...extra });

export const throwEntries: LogEntry[] = [
  entry(11, 1040.5, 'action-start', { cause: null, root: 11, resource: ORBITER, actionInProgress: { type: 'wait', seq: 11 }, changes: [ch(ORBITER, 2, orb({ actionInProgress: { type: 'wait', seq: 11 } }))] }),
  entry(12, 1041, 'action-end', { cause: 11, root: 11, resource: ORBITER, actionInProgress: { type: 'wait', seq: 11 }, changes: [ch(ORBITER, 3, orb())] }),
  entry(13, 1041, 'intent', { cause: null, root: 13,
    resource: ORBITER,
    controller: { kind: 'service', principal: 'orbital' },
    actionSpec: { type: 'throw', params: { at: { x: 5, y: 4 } } },
    reported: { draw: { dist: 'categorical', weights: [2.0, 1.0, 1.0], u: 0.61, value: 2 }, choices: ['step', 'wait', 'throw'] },
  }),
  entry(14, 1041, 'action-start', { cause: 13, root: 13, resource: ORBITER, actionInProgress: { type: 'throw', seq: 14 }, changes: [ch(ORBITER, 4, orb({ actionInProgress: { type: 'throw', seq: 14 } }))] }),
  entry(15, 1041, 'effect', { cause: 14, root: 13, loggedEffect: { type: 'create' }, changes: [created(PEBBLE, peb(2, 2))] }),
  entry(16, 1041, 'action-start', { cause: 15, root: 13, resource: PEBBLE, actionInProgress: { type: 'fly', seq: 16 }, changes: [ch(PEBBLE, 1, peb(2, 2, { actionInProgress: { type: 'fly', seq: 16 } }))] }),
  entry(17, 1042, 'action-end', { cause: 16, root: 13, resource: PEBBLE, actionInProgress: { type: 'fly', seq: 16 }, changes: [ch(PEBBLE, 2, peb(5, 4))] }),
  entry(18, 1042, 'effect', { cause: 17, root: 13, resource: PILLAR, loggedEffect: { type: 'damage' }, changes: [ch(PILLAR, 2, { entityKind: 'fixed', prototype: 'pillar', name: 'pillar', position: { x: 5, y: 4 }, radius: 0.5, damageable: true, hp: 97.5 })] }),
  entry(19, 1042, 'effect', { cause: 17, root: 13, loggedEffect: { type: 'break' } }),
  entry(20, 1042, 'effect', { cause: 19, root: 13, loggedEffect: { type: 'destroy' }, changes: [destroyed(PEBBLE)] }),
  entry(21, 1042, 'effect', { cause: 19, root: 13, loggedEffect: { type: 'create' }, changes: [created(RUBBLE, rub())] }),
  entry(22, 1042, 'action-start', { cause: 21, root: 13, resource: RUBBLE, actionInProgress: { type: 'decay', seq: 22 }, changes: [ch(RUBBLE, 1, rub({ actionInProgress: { type: 'decay', seq: 22 } }))] }),
  entry(23, 1062, 'action-end', { cause: 22, root: 13, resource: RUBBLE, actionInProgress: { type: 'decay', seq: 22 }, changes: [ch(RUBBLE, 2, rub())] }),
  entry(24, 1062, 'effect', { cause: 23, root: 13, loggedEffect: { type: 'destroy' }, changes: [destroyed(RUBBLE)] }),
  entry(25, 1063, 'action-start', { cause: null, root: 25, resource: ORBITER, actionInProgress: { type: 'wait', seq: 25 }, changes: [ch(ORBITER, 5, orb({ actionInProgress: { type: 'wait', seq: 25 } }))] }),
];
