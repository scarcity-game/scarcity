import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AreaAnimator } from './animator.ts';
import {
  ORBITER,
  RAT,
  at,
  entity,
  intent,
  positionIn,
  resetSeq,
  logEntry,
  stepEnd,
  waitEnd,
  world,
} from './animTestKit.ts';

const T0 = 1_000_000; // wall time, injected: the animator never reads a clock

beforeEach(() => resetSeq());

/** An advance of three beats: the orbiter steps (game time 11), a rat waits (11.5), the orbiter steps again (61). */
function threeBeats() {
  return [
    intent(10),
    stepEnd(11, ORBITER, at(4, 0), { reached: true }),
    waitEnd(11.5, RAT, 10, 10),
    stepEnd(61, ORBITER, at(4, 8), { reached: true }),
  ];
}

const before = () => world(entity(ORBITER, 0, 0), entity(RAT, 10, 10));
const after = () => world(entity(ORBITER, 4, 8), entity(RAT, 10, 10));

/** An animator that was handed the three beats, with the store already at the end state. */
function startThreeBeats() {
  const animator = new AreaAnimator();
  const truth = after();
  animator.hold(before(), T0);
  animator.push(threeBeats(), truth, T0);
  const orbiterAt = (ms: number) => {
    animator.tick(T0 + ms);
    return positionIn(animator.frame(truth, T0 + ms).resources, ORBITER);
  };
  return { animator, truth, orbiterAt };
}

/** A function pushing an advance: an intent at game time `time`, then the orbiter's step to (4, y). */
function stepPusher(animator: AreaAnimator, truth: ReturnType<typeof world>) {
  return (time: number, y: number, wall: number) =>
    animator.push(
      [intent(time), stepEnd(time + 1, ORBITER, at(4, y), { reached: true })],
      truth,
      wall,
    );
}

describe('AreaAnimator', () => {
  it('is idle at first: it shows the truth itself', () => {
    const animator = new AreaAnimator();
    const truth = before();
    expect(animator.busy).toBe(false);
    expect(animator.frame(truth, T0).resources).toBe(truth);
    animator.tick(T0 + 1000);
    expect(animator.busy).toBe(false);
  });

  it('plays one beat per game time, 250 ms each whatever the game time between them, in log order', () => {
    const { animator, truth, orbiterAt } = startThreeBeats();
    expect(animator.busy).toBe(true);
    // beat 1 (game time 11): the orbiter steps (0,0) -> (4,0)
    expect(orbiterAt(0)).toEqual(at(0, 0));
    expect(orbiterAt(125)).toEqual(at(2, 0));
    expect(orbiterAt(249)?.x).toBeCloseTo(3.984);
    // beat 2 (11.5, half a game time unit later): the rat waits; the orbiter is where beat 1 left it
    expect(orbiterAt(250)).toEqual(at(4, 0));
    expect(orbiterAt(499)).toEqual(at(4, 0));
    // beat 3 (61, fifty game time units later): the same 250 ms
    expect(orbiterAt(500)).toEqual(at(4, 0));
    expect(orbiterAt(625)).toEqual(at(4, 4));
    expect(animator.busy).toBe(true);
    // done: the truth
    expect(orbiterAt(750)).toEqual(at(4, 8));
    expect(animator.busy).toBe(false);
    expect(animator.frame(truth, T0 + 750).resources).toBe(truth);
  });

  it('a beat is as long as the speed says, read at the start of each beat', () => {
    let speed = 2;
    const animator = new AreaAnimator({ speed: () => speed });
    animator.push(threeBeats(), before(), T0);
    animator.tick(T0 + 124);
    expect(animator.busy).toBe(true); // still beat 1
    animator.tick(T0 + 125); // beat 1 (125 ms at speed 2) is over
    speed = 1;
    animator.tick(T0 + 250); // beat 2 began at 125 and took 125 ms; beat 3 now lasts 250 ms
    animator.tick(T0 + 499);
    expect(animator.busy).toBe(true);
    animator.tick(T0 + 500);
    expect(animator.busy).toBe(false);
  });

  it('holds the old state until the log entries arrive, then plays from it', () => {
    const animator = new AreaAnimator();
    const truth = after();
    animator.hold(before(), T0);
    // the store is already at the end state; the room still shows the old one
    expect(positionIn(animator.frame(truth, T0 + 100).resources, ORBITER)).toEqual(at(0, 0));
    animator.tick(T0 + 100);
    expect(animator.busy).toBe(true);
    animator.push(threeBeats(), truth, T0 + 200);
    expect(positionIn(animator.frame(truth, T0 + 200).resources, ORBITER)).toEqual(at(0, 0));
    expect(positionIn(animator.frame(truth, T0 + 325).resources, ORBITER)).toEqual(at(2, 0));
  });

  it('gives up holding when the log entries do not come: back to the truth', () => {
    const animator = new AreaAnimator({ holdMs: 1000 });
    const truth = after();
    animator.hold(before(), T0);
    animator.tick(T0 + 999);
    expect(animator.busy).toBe(true);
    animator.tick(T0 + 1000);
    expect(animator.busy).toBe(false);
    expect(animator.frame(truth, T0 + 1000).resources).toBe(truth);
  });

  it('entries that arrive first (no hold) play from the state shown when they arrive', () => {
    const animator = new AreaAnimator();
    animator.push(threeBeats(), before(), T0);
    expect(positionIn(animator.frame(after(), T0 + 125).resources, ORBITER)).toEqual(at(2, 0));
  });

  it('queues an advance that arrives mid-animation and plays it after the current one', () => {
    const animator = new AreaAnimator();
    const truth = world(entity(ORBITER, 4, 12), entity(RAT, 10, 10));
    animator.push(threeBeats(), before(), T0);
    animator.tick(T0 + 100);
    animator.push(
      [intent(70), stepEnd(71, ORBITER, at(4, 12), { reached: true })],
      truth,
      T0 + 100,
    );
    expect(animator.queued).toBe(1);
    expect(animator.skipped).toBe(0);
    // the current advance is not cut short
    animator.tick(T0 + 700);
    expect(positionIn(animator.frame(truth, T0 + 700).resources, ORBITER)?.y).toBeCloseTo(8 * 0.8);
    animator.tick(T0 + 750);
    // the queued one plays its beat from where the first ended: (4,8) -> (4,12)
    expect(positionIn(animator.frame(truth, T0 + 875).resources, ORBITER)).toEqual(at(4, 10));
    animator.tick(T0 + 1000);
    expect(animator.busy).toBe(false);
  });

  it('past one queued advance the older ones snap to their end state: skipped, not sped up', () => {
    const animator = new AreaAnimator();
    const truth = world(entity(ORBITER, 4, 20), entity(RAT, 10, 10));
    animator.push(threeBeats(), before(), T0);
    const pushStep = stepPusher(animator, truth);
    pushStep(70, 12, T0 + 10);
    pushStep(80, 16, T0 + 20);
    pushStep(90, 20, T0 + 30);
    // only the newest waits to play; the two before it will snap
    expect(animator.queued).toBe(1);
    animator.tick(T0 + 750); // the first advance is over
    // the snapped ones were applied at once (no beat of theirs plays) and the newest plays: (4,16) -> (4,20)
    expect([animator.skipped, animator.queued]).toEqual([2, 0]);
    expect(positionIn(animator.frame(truth, T0 + 750 + 125).resources, ORBITER)).toEqual(at(4, 18));
    // that beat takes the normal 250 ms
    animator.tick(T0 + 750 + 249);
    expect(animator.busy).toBe(true);
    animator.tick(T0 + 750 + 250);
    expect(animator.busy).toBe(false);
  });

  it('two advances in one push: the older snaps when a third waits behind a playing one', () => {
    const animator = new AreaAnimator();
    const truth = world(entity(ORBITER, 4, 16), entity(RAT, 10, 10));
    animator.push(threeBeats(), before(), T0);
    animator.push(
      [
        intent(70),
        stepEnd(71, ORBITER, at(4, 12), { reached: true }),
        intent(80),
        stepEnd(81, ORBITER, at(4, 16), { reached: true }),
      ],
      truth,
      T0 + 10,
    );
    animator.tick(T0 + 750);
    expect(animator.skipped).toBe(1);
    expect(positionIn(animator.frame(truth, T0 + 875).resources, ORBITER)).toEqual(at(4, 14));
  });

  it('an advance arriving with nothing waiting is not skipped', () => {
    const animator = new AreaAnimator();
    const truth = after();
    animator.push(threeBeats(), before(), T0);
    animator.tick(T0 + 750);
    animator.push(
      [intent(70), stepEnd(71, ORBITER, at(4, 12), { reached: true })],
      truth,
      T0 + 800,
    );
    expect(animator.skipped).toBe(0);
    expect(animator.busy).toBe(true);
  });

  it('an advance with nothing to show ends the hold at once', () => {
    const animator = new AreaAnimator();
    animator.hold(before(), T0);
    animator.push([intent(10)], after(), T0 + 5);
    expect(animator.busy).toBe(false);
  });

  it('tells subscribers when it becomes busy and when it is idle again', () => {
    const animator = new AreaAnimator();
    const seen: boolean[] = [];
    animator.subscribe(() => seen.push(animator.busy));
    animator.hold(before(), T0);
    animator.push(threeBeats(), after(), T0 + 1);
    animator.tick(T0 + 751);
    expect(seen[0]).toBe(true);
    expect(seen[seen.length - 1]).toBe(false);
  });

  it('reset drops everything and shows the truth', () => {
    const animator = new AreaAnimator();
    const listener = vi.fn();
    animator.subscribe(listener);
    animator.push(threeBeats(), before(), T0);
    animator.reset();
    expect(animator.busy).toBe(false);
    expect(animator.queued).toBe(0);
    expect(animator.skipped).toBe(0);
    expect(animator.frame(after(), T0 + 10).actedUpon.size).toBe(0);
  });

  it("names the targets of a beat's effects as acted upon", () => {
    const animator = new AreaAnimator();
    const hit = logEntry('effect', 11, { resource: RAT, loggedEffect: { type: 'damage' } });
    animator.hold(before(), T0);
    animator.push([intent(10), hit], after(), T0);
    const frame = animator.frame(after(), T0 + 10);
    expect([...frame.actedUpon]).toEqual([[RAT, 'damage']]);
    animator.tick(T0 + 300);
    expect(animator.frame(after(), T0 + 300).actedUpon.size).toBe(0); // the beat is over
  });
});
