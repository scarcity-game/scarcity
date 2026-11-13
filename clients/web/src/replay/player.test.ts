import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Replay } from './replay.ts';
import { ReplayPlayer } from './player.ts';
import { PEBBLE, PILLAR, throwEntries, throwSnapshot } from './throwFixture.ts';

describe('ReplayPlayer', () => {
  let player: ReplayPlayer;
  let t = 0;

  const make = () => new ReplayPlayer(new Replay(throwSnapshot, throwEntries), { now: () => t, intervalMs: 50 });

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    player.dispose();
  });

  const run = (ms: number) => {
    t += ms;
    vi.advanceTimersByTime(ms);
  };

  it('starts paused at the snapshot', () => {
    player = make();
    const state = player.getSnapshot();

    expect(state.playing).toBe(false);
    expect(state.speed).toBe(1);
    expect(state.gameTime).toBe(1040);
    expect(state.index).toBe(-1);
    expect(state.atEnd).toBe(false);
  });

  it('plays at 1x from wall time and applies entries when their time is reached', () => {
    player = make();
    player.play();

    run(500);
    const state1 = player.getSnapshot();
    expect(state1.gameTime).toBeCloseTo(1040.5, 1);
    expect(state1.seq).toBe(11);

    run(1000);
    const state2 = player.getSnapshot();
    expect(state2.gameTime).toBeCloseTo(1041.5, 1);
    expect(state2.seq).toBe(15);

    // Check that PEBBLE is present (created at seq 14)
    expect(state2.resources.has(PEBBLE)).toBe(true);
  });

  it('speed 4x', () => {
    player = make();
    player.setSpeed(4);
    player.play();

    run(500);
    const state = player.getSnapshot();
    expect(state.gameTime).toBeCloseTo(1042, 1);
    // every entry at time 1042 is applied: the pebble is gone and the rubble is there
    expect(state.resources.has(PEBBLE)).toBe(false);

    // Check that pillar has hp 97.5 (applied at seq 17)
    const pillar = state.resources.get(PILLAR);
    expect(pillar?.state.hp).toBe(97.5);
  });

  it('stops at the end of the range', () => {
    player = make();
    player.setSpeed(16);
    player.play();

    run(3000); // 16 * 3 = 48, which would go past the end
    const state = player.getSnapshot();

    expect(state.playing).toBe(false);
    expect(state.atEnd).toBe(true);
    expect(state.gameTime).toBe(1063);
    expect(state.seq).toBe(24);

    // Run more time - should not change anything
    run(1000);
    const state2 = player.getSnapshot();
    expect(state2.gameTime).toBe(1063);
    expect(state2.seq).toBe(24);
  });

  it('pause stops the clock', () => {
    player = make();
    player.play();
    run(300);
    player.pause();

    const g = player.getSnapshot().gameTime;
    run(1000);

    expect(player.getSnapshot().gameTime).toBe(g);
  });

  it('seekTime moves the scrubber back and forward', () => {
    player = make();

    // Seek to time 1062
    player.seekTime(1062);
    expect(player.getSnapshot().seq).toBe(23);

    // Seek to time 1040.7 (should be seq 11)
    player.seekTime(1040.7);
    const state1 = player.getSnapshot();
    expect(state1.seq).toBe(11);
    expect(state1.resources.has(PEBBLE)).toBe(false); // PEBBLE created at seq 14

    // Seek to time beyond end - should clamp
    player.seekTime(99999);
    const state2 = player.getSnapshot();
    expect(state2.gameTime).toBe(1063);
    expect(state2.atEnd).toBe(true);

    // Seek to time before start - should clamp
    player.seekTime(0);
    const state3 = player.getSnapshot();
    expect(state3.gameTime).toBe(1040);
    expect(state3.index).toBe(-1);
  });

  it('stepEntry steps one entry and pauses', () => {
    player = make();

    player.play();
    player.stepEntry(1); // step forward to seq 11
    const state1 = player.getSnapshot();
    expect(state1.playing).toBe(false);
    expect(state1.seq).toBe(11);
    expect(state1.gameTime).toBe(1040.5);

    player.stepEntry(1); // step forward to seq 12
    const state2 = player.getSnapshot();
    expect(state2.seq).toBe(12);

    player.stepEntry(-1); // step back to seq 11
    const state3 = player.getSnapshot();
    expect(state3.seq).toBe(11);

    player.stepEntry(-1); // step back to index -1
    const state4 = player.getSnapshot();
    expect(state4.index).toBe(-1);

    player.stepEntry(-1); // should stay at index -1
    const state5 = player.getSnapshot();
    expect(state5.index).toBe(-1);
  });

  it('jumpRoot goes to the next and previous root action that has effects', () => {
    player = make();

    // Jump to next root (seq 13, the throw)
    player.jumpRoot('next');
    const state1 = player.getSnapshot();
    expect(state1.seq).toBe(13);
    expect(state1.hasNextRoot).toBe(false);
    expect(state1.hasPrevRoot).toBe(false);

    // Try jumping again - should stay at seq 13
    player.jumpRoot('next');
    const state2 = player.getSnapshot();
    expect(state2.seq).toBe(13);

    // Seek to end and jump back
    player.seekSeq(24);
    player.jumpRoot('prev');
    const state3 = player.getSnapshot();
    expect(state3.seq).toBe(13);
  });

  it('seekSeq jumps to an entry by seq', () => {
    player = make();

    // Seek to seq 17
    player.seekSeq(17);
    const state1 = player.getSnapshot();
    expect(state1.seq).toBe(17);
    expect(state1.gameTime).toBe(1042);

    // Check pillar has hp 97.5 (applied at seq 17)
    const pillar = state1.resources.get(PILLAR);
    expect(pillar?.state.hp).toBe(97.5);

    // Seek to seq 14
    player.seekSeq(14);
    const state2 = player.getSnapshot();
    expect(state2.seq).toBe(14);

    // Check pillar has hp back to 100 (applied at seq 17, reverted at seq 14)
    const pillar2 = state2.resources.get(PILLAR);
    expect(pillar2?.state.hp).toBe(100);
  });

  it('subscribers are notified and the snapshot changes', () => {
    player = make();

    const listener = vi.fn();
    const before = player.getSnapshot();
    player.subscribe(listener);

    player.stepEntry(1);
    expect(listener).toHaveBeenCalled();

    const after = player.getSnapshot();
    expect(after).not.toBe(before);

    // Calling getSnapshot() twice without a change should return the same object
    const after2 = player.getSnapshot();
    expect(after2).toBe(after);
  });

  it('play from the end restarts from the start', () => {
    player = make();

    // Seek to time past the end
    player.seekTime(5000);

    // Play - should restart from beginning
    player.play();

    const state = player.getSnapshot();
    expect(state.playing).toBe(true);
    expect(state.gameTime).toBe(1040);
  });
});