import type { LogChange, LogEntry } from '../log/types.ts';
import type { Resource } from '../resources/cache.ts';

export const AREA = 'area:00000000-0000-4000-8000-000000000002';
export const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
export const RAT = 'entity:00000000-0000-4000-8000-000000000009';
export const PEBBLE = 'entity:00000000-0000-4000-8000-000000000005';

export const at = (x: number, y: number) => ({ x, y });

export const entity = (
  id: string,
  x: number,
  y: number,
  extra: Record<string, unknown> = {},
): Resource => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'mob',
  name: id.slice(-4),
  position: at(x, y),
  ...extra,
});

export const world = (...entities: Resource[]): Map<string, Resource> =>
  new Map<string, Resource>([
    [
      AREA,
      {
        id: AREA,
        resourceKind: 'area',
        version: 1,
        children: entities.map((e) => e.id),
        time: 0,
      },
    ],
    ...entities.map((e) => [e.id, e] as [string, Resource]),
  ]);

/** The position an entity has in a set of resources, or null. */
export const positionIn = (resources: ReadonlyMap<string, Resource>, id: string) => {
  const position = resources.get(id)?.position as { x: number; y: number } | undefined;
  return position ?? null;
};

let nextSeq = 1;
export const resetSeq = (n = 1) => {
  nextSeq = n;
};

export function logEntry(kind: string, time: number, extra: Partial<LogEntry> = {}): LogEntry {
  const seq = nextSeq++;
  return {
    seq,
    area: AREA,
    time,
    kind,
    cause: null,
    root: seq,
    resource: null,
    draws: [],
    changes: [],
    ...extra,
  };
}

export const intent = (time: number, resource = ORBITER, type = 'step') =>
  logEntry('intent', time, {
    resource,
    controller: { kind: 'service', principal: 'orbital' },
    actionSpec: { type },
  });

export const changed = (id: string, x: number, y: number, version = 2): LogChange => ({
  id,
  type: 'entity',
  op: 'changed',
  version,
  state: { entityKind: 'mob', name: id.slice(-4), position: at(x, y) },
});

interface StepEndSpec {
  time: number;
  id: string;
  to: { x: number; y: number };
  result: Record<string, unknown>;
  /** Where the entity's state is after the step; `to` by default. */
  state?: { x: number; y: number };
}

/** The end of a step: the entity's state is where the step stopped (`state`, by default `to`). */
export const stepEndWith = ({ time, id, to, result, state = to }: StepEndSpec) =>
  logEntry('action-end', time, {
    resource: id,
    actionInProgress: { type: 'step', params: { to }, start: time - 1, end: time },
    result,
    changes: [changed(id, state.x, state.y)],
  });

/** The end of a step that reached `to`. */
export const stepEnd = (
  time: number,
  id: string,
  to: { x: number; y: number },
  result: Record<string, unknown>,
) => stepEndWith({ time, id, to, result });

/** The end of a wait: the entity is where it was. */
export const waitEnd = (time: number, id: string, x: number, y: number) =>
  logEntry('action-end', time, {
    resource: id,
    actionInProgress: { type: 'wait', params: { duration: 1 } },
    changes: [changed(id, x, y)],
  });
