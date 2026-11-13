import { describe, expect, it } from 'vitest';
import { Replay } from './replay.ts';
import {
  AREA,
  PILLAR,
  ORBITER,
  PEBBLE,
  RUBBLE,
  throwSnapshot,
  throwEntries
} from './throwFixture.ts';

describe('Replay', () => {
  // Test 1: starts at the snapshot
  it('starts at the snapshot', () => {
    const replay = new Replay(throwSnapshot, throwEntries);
    expect(replay.index).toBe(-1);
    expect(replay.seq).toBe(10);
    expect(replay.time).toBe(1040);

    // Check resources
    const resources = Array.from(replay.resources.entries());
    expect(resources.length).toBe(3); // AREA, PILLAR, ORBITER

    const resourceMap = new Map(resources);
    expect(resourceMap.has(AREA)).toBe(true);
    expect(resourceMap.has(PILLAR)).toBe(true);
    expect(resourceMap.has(ORBITER)).toBe(true);
    expect(resourceMap.has(PEBBLE)).toBe(false);

    // Check area children
    const areaResource = resourceMap.get(AREA)!;
    expect(areaResource.children).toEqual([PILLAR, ORBITER]);
  });

  // Test 2: pebble exists from seq 14 to 18
  it('pebble exists from seq 14 to 18', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Before pebble creation (seq 13)
    replay.seekSeq(13);
    expect(replay.resources.has(PEBBLE)).toBe(false);
    const areaResource = replay.resources.get(AREA)!;
    expect(areaResource.children).not.toContain(PEBBLE);

    // At pebble creation (seq 14)
    replay.seekSeq(14);
    expect(replay.resources.has(PEBBLE)).toBe(true);
    const areaResource2 = replay.resources.get(AREA)!;
    expect(areaResource2.children).toContain(PEBBLE);

    // At seq 18 (still present)
    replay.seekSeq(18);
    expect(replay.resources.has(PEBBLE)).toBe(true);
    const areaResource3 = replay.resources.get(AREA)!;
    expect(areaResource3.children).toContain(PEBBLE);

    // After pebble destruction (seq 19)
    replay.seekSeq(19);
    expect(replay.resources.has(PEBBLE)).toBe(false);
    const areaResource4 = replay.resources.get(AREA)!;
    expect(areaResource4.children).not.toContain(PEBBLE);
  });

  // Test 3: pillar hp drops at seq 17
  it('pillar hp drops at seq 17', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Before damage (seq 16)
    replay.seekSeq(16);
    const pillarBefore = replay.resources.get(PILLAR)!;
    expect(pillarBefore.state.hp).toBe(100);
    expect(pillarBefore.version).toBe(1);

    // After damage (seq 17)
    replay.seekSeq(17);
    const pillarAfter = replay.resources.get(PILLAR)!;
    expect(pillarAfter.state.hp).toBe(97.5);
    expect(pillarAfter.version).toBe(2);
  });

  // Test 4: rubble appears at 20 and is gone at 23
  it('rubble appears at 20 and is gone at 23', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Check presence at various seqs
    replay.seekSeq(19);
    expect(replay.resources.has(RUBBLE)).toBe(false);

    replay.seekSeq(20);
    expect(replay.resources.has(RUBBLE)).toBe(true);
    const areaResource = replay.resources.get(AREA)!;
    expect(areaResource.children).toContain(RUBBLE);

    replay.seekSeq(22);
    expect(replay.resources.has(RUBBLE)).toBe(true);
    const areaResource2 = replay.resources.get(AREA)!;
    expect(areaResource2.children).toContain(RUBBLE);

    replay.seekSeq(23);
    expect(replay.resources.has(RUBBLE)).toBe(false);
    const areaResource3 = replay.resources.get(AREA)!;
    expect(areaResource3.children).not.toContain(RUBBLE);
  });

  // Test 5: jump back equals rebuild
  it('jump back equals rebuild', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    for (let i = -1; i < replay.length; i++) {
      // Seek forward to the end first
      replay.seekIndex(replay.length - 1);

      // Then seek backwards to index i
      replay.seekIndex(i);

      // Compare with rebuildAt result
      expect(Array.from(replay.resources.entries())).toEqual(
        Array.from(replay.rebuildAt(i).entries())
      );
    }
  });

  // Test 6: stepping forward equals rebuild
  it('stepping forward equals rebuild', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Fresh replay
    for (let i = 0; i < replay.length; i++) {
      // Seek to index i
      replay.seekIndex(i);

      // Compare with rebuildAt result
      expect(Array.from(replay.resources.entries())).toEqual(
        Array.from(replay.rebuildAt(i).entries())
      );
    }
  });

  // Test 7: same-time entries apply in seq order
  it('same-time entries apply in seq order', () => {
    const snapshot = {
      ...throwSnapshot,
      changes: [
        { id: AREA, type: 'area', op: 'snapshot', version: 1, state: { width: 1, height: 1 } },
        { id: PILLAR, type: 'entity', op: 'snapshot', version: 1, state: { n: 0 } }
      ]
    };

    const entries = [
      // Entry with seq 3 (applied last)
      {
        ...throwSnapshot,
        seq: 3,
        time: 11,
        kind: 'effect',
        cause: 2,
        root: 2,
        changes: [{ id: PILLAR, type: 'entity', op: 'changed', version: 2, state: { n: 2 } }]
      },
      // Entry with seq 2 (applied first)
      {
        ...throwSnapshot,
        seq: 2,
        time: 11,
        kind: 'effect',
        cause: null,
        root: 2,
        changes: [{ id: PILLAR, type: 'entity', op: 'changed', version: 2, state: { n: 1 } }]
      }
    ];

    const replay = new Replay(snapshot, entries);

    // Seek to time 11 (should apply both entries)
    replay.seekTime(11);
    expect(replay.resources.get(PILLAR)!.state.n).toBe(2); // seq 3 applied last

    // Seek to seq 2 specifically
    replay.seekSeq(2);
    expect(replay.resources.get(PILLAR)!.state.n).toBe(1);
  });

  // Test 8: seekTime applies every entry up to and including the time
  it('seekTime applies every entry up to and including the time', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Before any entries with time 1041
    replay.seekTime(1041);
    expect(replay.seq).toBe(15); // seq 15 is the last entry <= time 1041

    // At time 1042 (should be at seq 21)
    replay.seekTime(1042);
    expect(replay.seq).toBe(21); // seq 21 is the last entry <= time 1042

    // Before any entries
    replay.seekTime(1000);
    expect(replay.index).toBe(-1); // No entries before time 1000

    // After all entries
    replay.seekTime(5000);
    expect(replay.index).toBe(replay.length - 1); // Last entry
  });

  // Test 9: a null version still applies the state
  it('a null version still applies the state', () => {
    const snapshot = {
      ...throwSnapshot,
      changes: [
        { id: AREA, type: 'area', op: 'snapshot', version: 4, state: { name: 'test' } },
        { id: PILLAR, type: 'entity', op: 'snapshot', version: 4, state: { n: 0 } }
      ]
    };

    const entries = [
      {
        ...throwSnapshot,
        seq: 1,
        time: 1000,
        kind: 'effect',
        cause: null,
        root: 1,
        changes: [{ id: PILLAR, type: 'entity', op: 'changed', version: null, state: { n: 9 } }]
      }
    ];

    const replay = new Replay(snapshot, entries);
    replay.seekIndex(0);

    expect(replay.resources.get(PILLAR)!.state.n).toBe(9);
    expect(replay.resources.get(PILLAR)!.version).toBe(4); // Version unchanged
  });

  // Test 10: created then destroyed in one entry batch leaves nothing
  it('created then destroyed in one entry batch leaves nothing', () => {
    const snapshot = {
      ...throwSnapshot,
      changes: [
        { id: AREA, type: 'area', op: 'snapshot', version: 1, state: { name: 'test' } }
      ]
    };

    const entries = [
      {
        ...throwSnapshot,
        seq: 1,
        time: 1000,
        kind: 'effect',
        cause: null,
        root: 1,
        changes: [
          { id: PEBBLE, type: 'entity', op: 'created', parent: AREA, version: null, state: { name: 'x' } },
          { id: PEBBLE, type: 'entity', op: 'destroyed' }
        ]
      }
    ];

    const replay = new Replay(snapshot, entries);
    replay.seekIndex(0);

    expect(replay.resources.has(PEBBLE)).toBe(false);
    expect(replay.resources.get(AREA)!.children).toEqual([]);
  });

  // Test 11: effect roots
  it('effect roots', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // At index -1 (before any entries)
    expect(replay.prevEffectRoot()).toBeNull();
    expect(replay.nextEffectRoot()).toBe(2); // Index of seq 13

    // After seeking to effect root
    replay.seekIndex(2); // seq 13
    expect(replay.prevEffectRoot()).toBeNull();
    expect(replay.nextEffectRoot()).toBeNull();

    // After seeking to end
    replay.seekIndex(replay.length - 1);
    expect(replay.prevEffectRoot()).toBe(2); // Index of seq 13
    expect(replay.nextEffectRoot()).toBeNull();
  });

  // Test 12: resources is a new map only when the state changes
  it('resources is a new map only when the state changes', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    const a = replay.resources;
    replay.seekIndex(0);
    const b = replay.resources;
    expect(b).not.toBe(a); // Should be different object

    replay.seekIndex(0);
    expect(replay.resources).toBe(b); // Should be same object (no change)
  });

  // Test 13: areaEntities lists the area children in order
  it('areaEntities lists the area children in order', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    replay.seekSeq(14);
    const entities = replay.areaEntities(AREA);
    expect(entities.map(r => r.id)).toEqual([PILLAR, ORBITER, PEBBLE]);
  });
});