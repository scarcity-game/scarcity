// The mocked world: one game, one area with a room of entities, and a log. The
// log is the replay fixture of the unit tests (src/replay/throwFixture.ts) with a
// snapshot that holds the whole room, so the live view and the replay agree.
import { AREA, ORBITER, PILLAR, RUBBLE, throwEntries, throwSnapshot } from '../src/replay/throwFixture.ts';
import type { LogChange, LogEntry } from '../src/log/types.ts';

export { AREA, PILLAR, RUBBLE };
export const GAME = 'game:00000000-0000-4000-8000-000000000001';
export const HUMANOID = ORBITER;
export const MOUSE = 'entity:00000000-0000-4000-8000-000000000007';
export const STAIRS = 'entity:00000000-0000-4000-8000-000000000008';

export const USER = { id: '6f1c2a9e-1111-4c2e-9a77-000000000001', username: 'alice', createdAt: '2026-11-09T10:15:30.123456Z' };

const entity = (id: string, fields: Record<string, unknown>) => ({
  ...{ id, resourceKind: 'entity', version: 1, children: [] },
  ...fields,
});

const body = { overlap: false, damageable: true };
const humanoidState = { entityKind: 'mob', prototype: 'orbiter', name: 'orbiter', position: { x: 2, y: 2 }, mass: 70, radius: 0.25, speed: 1.4, ...body, hp: 20 };
const mouseState = { entityKind: 'mob', prototype: 'mouse', name: 'mouse', position: { x: 7.5, y: 6 }, mass: 20, radius: 0.04, speed: 0.5, ...body, hp: 3, behaviour: { kind: 'random' } };
const pillarState = { entityKind: 'fixed', prototype: 'pillar', name: 'pillar', position: { x: 5, y: 4 }, mass: 6360000, radius: 0.5, overlap: false, damageable: true, hp: 100 };
const rubbleState = { entityKind: 'fixed', prototype: 'rubble', name: 'rubble', position: { x: 3.5, y: 6.5 }, mass: 40, radius: 0.3, overlap: true };
const stairsState = { entityKind: 'fixed', prototype: 'stairs-down', name: 'stairs down', position: { x: 9, y: 1 }, mass: 1, radius: 0.4, overlap: true };

type Box = [number, number, number, number];

/** A wall: a `stone-wall` fixed entity with a rectangle for a polygon; its position is the centroid. */
const wallState = (name: string, [x0, y0, x1, y1]: Box) => ({
  entityKind: 'fixed', prototype: 'stone-wall', name, material: 'stone', mass: 5000000, overlap: false, damageable: false,
  polygon: [{ x: x0, y: y0 }, { x: x1, y: y0 }, { x: x1, y: y1 }, { x: x0, y: y1 }],
  position: { x: (x0 + x1) / 2, y: (y0 + y1) / 2 },
});
const wallsOf = (first: number, walls: Array<[string, Box]>) =>
  walls.map(([name, box], i) => ({ id: `entity:00000000-0000-4000-8000-0000000002${String(first + i).padStart(2, '0')}`, state: wallState(name, box) }));

/** Four walls around the 10 x 8 test room, leaving no gap (the corners belong to the north and south walls). */
const roomWalls = wallsOf(0, [
  ['north wall', [-0.5, 8, 10.5, 8.5]],
  ['south wall', [-0.5, -0.5, 10.5, 0]],
  ['west wall', [-0.5, 0, 0, 8]],
  ['east wall', [10, 0, 10.5, 8]],
]);
export const WALL = roomWalls[2]!.id;

export const entities = [
  ...roomWalls.map((w) => entity(w.id, w.state)),
  entity(PILLAR, pillarState),
  entity(HUMANOID, humanoidState),
  entity(MOUSE, mouseState),
  entity(RUBBLE, rubbleState),
  entity(STAIRS, stairsState),
];

export const game = { id: GAME, resourceKind: 'game', version: 2, children: [AREA], name: 'Test area' };
export const area = {
  ...{ id: AREA, resourceKind: 'area', version: 3, children: entities.map((e) => e.id) },
  ...{ name: 'Test room', time: 1042, depth: 1 },
};

const snapshotChange = (id: string, state: Record<string, unknown>): LogChange => ({ id, type: 'entity', op: 'snapshot', version: 1, state });
const areaChange = throwSnapshot.changes[0]!;

// The snapshot holds the room at the start of the throw. The throw's pebble and
// rubble come and go in the entries after it (the live room shows the rubble).
const snapshot: LogEntry = {
  ...throwSnapshot,
  changes: [
    areaChange,
    snapshotChange(PILLAR, pillarState),
    snapshotChange(HUMANOID, humanoidState),
    snapshotChange(MOUSE, mouseState),
    snapshotChange(STAIRS, stairsState),
    ...roomWalls.map((w) => snapshotChange(w.id, w.state)),
  ],
};

// After the throw the orbiter walks: the replay stands on the start of a `step`, so its sprite is moving.
const walk: LogEntry = {
  ...{ seq: 26, area: AREA, time: 1063.5, kind: 'action-start', cause: null, root: 26, resource: HUMANOID },
  ...{ draws: [], actionInProgress: { type: 'step', params: { to: { x: 4, y: 2 } }, seq: 26 } },
  changes: [
    snapshotChange(HUMANOID, { ...humanoidState, actionInProgress: { type: 'step', params: { to: { x: 4, y: 2 } }, seq: 26 } }),
  ].map((c) => ({ ...c, op: 'changed' as const, version: 6 })),
};

// A second later the step (a channel) has moved on: speed 1.4, so 1.4 of its 2.0, and the log says where it got to.
const stepAction = { type: 'step', params: { to: { x: 4, y: 2 } }, start: 1063.5, end: 1064.93, timing: 'channel', seq: 26 };
const walkOn: LogEntry = {
  ...{ seq: 27, area: AREA, time: 1064.5, kind: 'action-progress', cause: 26, root: 26, resource: HUMANOID },
  ...{ draws: [], actionInProgress: stepAction },
  result: { from: { x: 2, y: 2 }, at: { x: 3.4, y: 2 }, distance: 1.4, blocked: false },
  changes: [
    snapshotChange(HUMANOID, { ...humanoidState, position: { x: 3.4, y: 2 }, actionInProgress: stepAction }),
  ].map((c) => ({ ...c, op: 'changed' as const, version: 6 })),
};

// Half a second on, something acts upon the orbiter: the mouse's recovery is logged, the step is interrupted where it got to.
const bite: LogEntry = {
  ...{ seq: 28, area: AREA, time: 1065, kind: 'effect', cause: null, root: 28, resource: null, draws: [], changes: [] },
  loggedEffect: { type: 'damage', params: { target: HUMANOID } },
  actedUpon: [
    { resource: HUMANOID, interrupted: true },
    { resource: MOUSE, interrupted: false },
  ],
  result: { hpBefore: 20, hpAfter: 19 },
};
const stoppedAt = { x: 3.65, y: 2 };
const interrupted: LogEntry = {
  ...{ seq: 29, area: AREA, time: 1065, kind: 'action-interrupted', cause: 28, root: 28, resource: HUMANOID },
  ...{ draws: [], actionInProgress: { ...stepAction, end: 1065 } },
  result: { reached: false, to: { x: 4, y: 2 }, stoppedAt },
  changes: [snapshotChange(HUMANOID, { ...humanoidState, position: stoppedAt, hp: 19 })].map((c) => ({ ...c, op: 'changed' as const, version: 7 })),
};

export const log: LogEntry[] = [snapshot, ...throwEntries, walk, walkOn, bite, interrupted];

/** A second game holding only `area`: its game page lists the one area. */
export const SOLO_GAME = 'game:00000000-0000-4000-8000-000000000002';
export const soloGame = { id: SOLO_GAME, resourceKind: 'game', version: 1, children: [AREA], name: 'Solo area' };

// A dive level: a 14 x 9 room, its outer walls and two inner walls with a gap each, the diver and the stairs down.
export const DIVE = 'area:00000000-0000-4000-8000-000000000300';
const diveWalls = wallsOf(10, [
  ['north wall', [-0.5, 9, 14.5, 9.5]],
  ['south wall', [-0.5, -0.5, 14.5, 0]],
  ['west wall', [-0.5, 0, 0, 9]],
  ['east wall', [14, 0, 14.5, 9]],
  ['inner wall A', [4, 0, 4.6, 6]],
  ['inner wall B', [9, 3, 9.6, 9]],
]);
const diveEntities = [
  ...diveWalls.map((w) => entity(w.id, w.state)),
  entity('entity:00000000-0000-4000-8000-000000000315', { ...humanoidState, name: 'diver', prototype: 'humanoid', position: { x: 1.5, y: 1.5 }, controller: { kind: 'service', principal: 'diver' } }),
  entity('entity:00000000-0000-4000-8000-000000000316', { ...stairsState, position: { x: 12.5, y: 7.5 } }),
];
export const dive = {
  ...{ id: DIVE, resourceKind: 'area', version: 1, children: diveEntities.map((e) => e.id) },
  ...{ name: 'Dive level 2', time: 310, depth: 2 },
};
export { diveEntities };
