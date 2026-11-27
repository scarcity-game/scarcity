import { beforeEach, describe, expect, it } from 'vitest';
import {
  BEAT_MS,
  animationSpeed,
  applyAdvance,
  beatMs,
  beatProgress,
  lerpPoint,
  motionTarget,
  planBeat,
  resourcesAt,
  splitAdvances,
  toBeats,
} from './animation.ts';
import {
  AREA,
  ORBITER,
  PEBBLE,
  RAT,
  at,
  changed,
  entity,
  intent,
  logEntry,
  positionIn,
  resetSeq,
  stepEnd,
  stepEndWith,
  waitEnd,
  world,
} from './animTestKit.ts';

beforeEach(() => resetSeq());

describe('the beat', () => {
  it('is one constant behind a single speed value', () => {
    expect(BEAT_MS).toBe(250);
    expect(animationSpeed).toBe(1);
    expect(beatMs()).toBe(250);
    expect(beatMs(2)).toBe(125);
    expect(beatMs(0.5)).toBe(500);
  });

  it('progress runs 0 to 1 through a beat on the wall time it is given', () => {
    const start = 1_000_000;
    expect(beatProgress(start, start, 250)).toBe(0);
    expect(beatProgress(start, start + 62.5, 250)).toBeCloseTo(0.25);
    expect(beatProgress(start, start + 125, 250)).toBeCloseTo(0.5);
    expect(beatProgress(start, start + 250, 250)).toBe(1);
    expect(beatProgress(start, start + 9999, 250)).toBe(1);
    expect(beatProgress(start, start - 5, 250)).toBe(0);
  });

  it('progress is 1 for a beat with no length', () => {
    expect(beatProgress(0, 0, 0)).toBe(1);
  });
});

describe('beats and advances', () => {
  it('entries at the same game time are one beat, in log order; a new time is a new beat', () => {
    const a = logEntry('action-start', 10, { resource: ORBITER });
    const b = logEntry('effect', 10);
    const c = logEntry('action-end', 11, { resource: RAT });
    const d = logEntry('action-end', 11, { resource: ORBITER });
    const e = logEntry('action-end', 61, { resource: RAT });
    const beats = toBeats([a, b, c, d, e]);
    expect(beats.map((x) => x.time)).toEqual([10, 11, 61]);
    expect(beats[0]!.entries).toEqual([a, b]);
    expect(beats[1]!.entries).toEqual([c, d]);
  });

  it('a wait of 1.0 and a short step each get one beat, whatever their game time', () => {
    const wait = waitEnd(11, RAT, 1, 1);
    const step = stepEnd(11.05, ORBITER, at(1, 0), { reached: true });
    const longWait = waitEnd(71.05, RAT, 1, 1);
    const beats = toBeats([wait, step, longWait]);
    expect(beats).toHaveLength(3);
    expect(beats.map((b) => b.entries.length)).toEqual([1, 1, 1]);
  });

  it('intents and snapshots show nothing: they make no beat', () => {
    const entries = [
      logEntry('snapshot', 0),
      intent(10),
      logEntry('action-start', 10),
      logEntry('intent', 10),
    ];
    const beats = toBeats(entries);
    expect(beats).toHaveLength(1);
    expect(beats[0]!.entries.map((e) => e.kind)).toEqual(['action-start']);
  });

  it('an intent starts an advance; snapshots are dropped; entries before the first intent are their own', () => {
    const lead = logEntry('action-end', 9, { resource: RAT });
    const snap = logEntry('snapshot', 9);
    const i1 = intent(10);
    const s1 = logEntry('action-start', 10, { resource: ORBITER });
    const i2 = intent(12);
    const s2 = logEntry('action-start', 12, { resource: ORBITER });
    const advances = splitAdvances([lead, snap, i1, s1, i2, s2]);
    expect(advances.map((a) => a.entries)).toEqual([[lead], [i1, s1], [i2, s2]]);
    expect(advances.map((a) => a.beats.length)).toEqual([1, 1, 1]);
    expect(splitAdvances([])).toEqual([]);
    expect(splitAdvances([snap])).toEqual([]);
  });
});

describe('a step along its segment', () => {
  const before = world(entity(ORBITER, 2, 2));

  it('is interpolated from where it was to where it is going', () => {
    const plan = planBeat(before, toBeats([stepEnd(11, ORBITER, at(6, 2), { reached: true })])[0]!);
    expect(plan.motions.get(ORBITER)).toEqual({ from: at(2, 2), to: at(6, 2) });
    expect(positionIn(resourcesAt(plan, 0), ORBITER)).toEqual(at(2, 2));
    expect(positionIn(resourcesAt(plan, 0.25), ORBITER)).toEqual(at(3, 2));
    expect(positionIn(resourcesAt(plan, 0.5), ORBITER)).toEqual(at(4, 2));
    expect(positionIn(resourcesAt(plan, 1), ORBITER)).toEqual(at(6, 2));
  });

  it('moves part of the way in each beat of an action-progress, then the rest at its end', () => {
    const progress = logEntry('action-progress', 11, { resource: ORBITER, changes: [changed(ORBITER, 4, 2)] });
    const beats = toBeats([progress, stepEnd(12, ORBITER, at(6, 2), { reached: true })]);
    expect(beats.map((b) => b.time)).toEqual([11, 12]);
    const first = planBeat(before, beats[0]!);
    expect(first.motions.get(ORBITER)).toEqual({ from: at(2, 2), to: at(4, 2) });
    expect(positionIn(resourcesAt(first, 0.5), ORBITER)).toEqual(at(3, 2));
    const second = planBeat(first.after, beats[1]!);
    expect(second.motions.get(ORBITER)).toEqual({ from: at(4, 2), to: at(6, 2) });
  });

  it('goes to stoppedAt, not to params.to, when it was blocked', () => {
    const blocked = stepEndWith({
      time: 11,
      id: ORBITER,
      to: at(9, 2),
      result: { reached: false, to: at(9, 2), stoppedAt: at(4, 2) },
      state: at(4, 2),
    });
    const plan = planBeat(before, toBeats([blocked])[0]!);
    expect(plan.motions.get(ORBITER)).toEqual({ from: at(2, 2), to: at(4, 2) });
    expect(positionIn(resourcesAt(plan, 0.5), ORBITER)).toEqual(at(3, 2));
    expect(positionIn(resourcesAt(plan, 1), ORBITER)).toEqual(at(4, 2));
  });

  it('stops an interrupted step at the entry\'s position, never at params.to', () => {
    const interrupted = logEntry('action-interrupted', 11, {
      resource: ORBITER,
      actionInProgress: { type: 'step', params: { to: at(9, 2) }, start: 10, end: 11 },
      result: { reached: false, to: at(9, 2), stoppedAt: at(3.5, 2) },
      changes: [changed(ORBITER, 3.5, 2)],
    });
    const beats = toBeats([interrupted]);
    expect(beats.map((b) => b.time)).toEqual([11]);
    const plan = planBeat(before, beats[0]!);
    expect(plan.motions.get(ORBITER)).toEqual({ from: at(2, 2), to: at(3.5, 2) });
    expect(positionIn(resourcesAt(plan, 1), ORBITER)).toEqual(at(3.5, 2));
  });

  it('takes stoppedAt for an interrupted step whose change has no position, and ignores contacts', () => {
    const interrupted = logEntry('action-interrupted', 11, {
      resource: ORBITER,
      actionInProgress: { type: 'step' },
      result: { stoppedAt: at(3, 2) },
      changes: [{ id: ORBITER, type: 'entity', op: 'changed' }],
    });
    expect(motionTarget(interrupted, interrupted.changes[0]!)).toEqual(at(3, 2));
    const contact = logEntry('contact', 11, { resource: ORBITER, result: { resources: [ORBITER, RAT] } });
    expect(toBeats([contact, interrupted])).toHaveLength(1);
  });

  it('marks everything an effect acted upon, interrupted or in recovery', () => {
    const effect = logEntry('effect', 11, {
      loggedEffect: { type: 'damage' },
      actedUpon: [
        { resource: ORBITER, interrupted: true },
        { resource: RAT, interrupted: false },
      ],
    });
    const plan = planBeat(before, toBeats([effect])[0]!);
    expect([...plan.actedUpon.keys()].sort()).toEqual([ORBITER, RAT].sort());
    expect(plan.actedUpon.get(RAT)).toBe('damage');
  });

  it('motionTarget: stoppedAt when blocked, params.to when reached, else the resulting state', () => {
    const change = changed(ORBITER, 4, 2);
    const blocked = stepEnd(11, ORBITER, at(9, 2), { reached: false, stoppedAt: at(4, 2) });
    const reached = stepEnd(11, ORBITER, at(9, 2), { reached: true, stoppedAt: at(9, 2) });
    expect(motionTarget(blocked, change)).toEqual(at(4, 2));
    expect(motionTarget(reached, change)).toEqual(at(9, 2));
    // a malformed stoppedAt falls back to params.to, a missing one to the state
    const odd = stepEnd(11, ORBITER, at(9, 2), { reached: false, stoppedAt: 'x' });
    expect(motionTarget(odd, change)).toEqual(at(9, 2));
    const bare = logEntry('action-end', 11, {
      resource: ORBITER,
      actionInProgress: { type: 'step' },
      changes: [change],
    });
    expect(motionTarget(bare, change)).toEqual(at(4, 2));
    // an entry that is not the end of the action moves the entity where its state says
    const effect = logEntry('effect', 11, { loggedEffect: { type: 'damage' } });
    expect(motionTarget(effect, change)).toEqual(at(4, 2));
    expect(motionTarget(effect, { id: ORBITER, type: 'entity', op: 'changed' })).toBeNull();
  });

  it('a step that goes nowhere makes no motion', () => {
    const plan = planBeat(before, toBeats([stepEnd(11, ORBITER, at(2, 2), { reached: true })])[0]!);
    expect(plan.motions.size).toBe(0);
  });

  it('lerpPoint is linear', () => {
    expect(lerpPoint(at(0, 0), at(10, -4), 0.5)).toEqual(at(5, -2));
  });
});

describe('entries at the same game time play together', () => {
  it('two entities that move at the same time are both on their segments in one frame', () => {
    const state = world(entity(ORBITER, 0, 0), entity(RAT, 10, 10));
    const beats = toBeats([
      stepEnd(11, ORBITER, at(4, 0), { reached: true }),
      stepEnd(11, RAT, at(10, 6), { reached: true }),
    ]);
    expect(beats).toHaveLength(1);
    const plan = planBeat(state, beats[0]!);
    const half = resourcesAt(plan, 0.5);
    expect(positionIn(half, ORBITER)).toEqual(at(2, 0));
    expect(positionIn(half, RAT)).toEqual(at(10, 8));
  });

  it('several entries for one entity in a beat make one segment, first position to last', () => {
    const state = world(entity(ORBITER, 0, 0));
    const first = logEntry('effect', 5, {
      loggedEffect: { type: 'x' },
      changes: [changed(ORBITER, 2, 0)],
    });
    const second = logEntry('effect', 5, {
      loggedEffect: { type: 'x' },
      changes: [changed(ORBITER, 4, 0)],
    });
    const plan = planBeat(state, toBeats([first, second])[0]!);
    expect(plan.motions.get(ORBITER)).toEqual({ from: at(0, 0), to: at(4, 0) });
  });

  it('what is created in the beat is there from its start, what is destroyed stays until its end', () => {
    const state = world(entity(PEBBLE, 2, 2));
    const destroy = logEntry('effect', 7, {
      loggedEffect: { type: 'destroy' },
      changes: [{ id: PEBBLE, type: 'entity', op: 'destroyed' }],
    });
    const create = logEntry('effect', 7, {
      loggedEffect: { type: 'create' },
      changes: [
        {
          id: RAT,
          type: 'entity',
          op: 'created',
          parent: AREA,
          version: 1,
          state: { entityKind: 'fixed', position: at(5, 4) },
        },
      ],
    });
    const plan = planBeat(state, toBeats([destroy, create])[0]!);
    const early = resourcesAt(plan, 0.1);
    expect(early.has(PEBBLE)).toBe(true);
    expect(early.has(RAT)).toBe(true);
    const done = resourcesAt(plan, 1);
    expect(done.has(PEBBLE)).toBe(false);
    expect(done.has(RAT)).toBe(true);
  });

  it('a wait moves nothing', () => {
    const state = world(entity(RAT, 3, 3));
    const plan = planBeat(state, toBeats([waitEnd(11, RAT, 3, 3)])[0]!);
    expect(plan.motions.size).toBe(0);
    expect(positionIn(resourcesAt(plan, 0.5), RAT)).toEqual(at(3, 3));
  });
});

describe('applyAdvance (the snap)', () => {
  it('applies every entry of an advance at once', () => {
    const state = world(entity(ORBITER, 0, 0), entity(RAT, 10, 10));
    const [advance] = splitAdvances([
      intent(10),
      stepEnd(11, ORBITER, at(4, 0), { reached: true }),
      stepEnd(12, ORBITER, at(4, 3), { reached: true }),
    ]);
    const end = applyAdvance(state, advance!);
    expect(positionIn(end, ORBITER)).toEqual(at(4, 3));
    expect(positionIn(end, RAT)).toEqual(at(10, 10));
    expect(positionIn(state, ORBITER)).toEqual(at(0, 0)); // the state it started from is untouched
  });
});
