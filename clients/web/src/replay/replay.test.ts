import { describe, expect, it } from 'vitest';
import { Replay } from './replay.ts';
import {
  AREA,
  entry,
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

  // Test 2: pebble exists from seq 15 to 19
  it('pebble exists from seq 15 to 19', () => {
    const replay = new Replay(throwSnapshot, throwEntries);
    // Whether the pebble is in the resources, and in the area's children, at `seq`
    const pebbleAt = (seq: number) => {
      replay.seekSeq(seq);
      return [replay.resources.has(PEBBLE), replay.resources.get(AREA)!.children.includes(PEBBLE)];
    };

    expect(pebbleAt(14)).toEqual([false, false]); // before the pebble's creation
    expect(pebbleAt(15)).toEqual([true, true]); // at its creation
    expect(pebbleAt(19)).toEqual([true, true]); // still present
    expect(pebbleAt(20)).toEqual([false, false]); // after its destruction
  });

  // Test 3: pillar hp drops at seq 18
  it('pillar hp drops at seq 18', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Before damage (seq 17)
    replay.seekSeq(17);
    const pillarBefore = replay.resources.get(PILLAR)!;
    expect(pillarBefore.hp).toBe(100);
    expect(pillarBefore.version).toBe(1);

    // After damage (seq 18)
    replay.seekSeq(18);
    const pillarAfter = replay.resources.get(PILLAR)!;
    expect(pillarAfter.hp).toBe(97.5);
    expect(pillarAfter.version).toBe(2);
  });

  // Test 4: rubble appears at 21 and is gone at 24
  it('rubble appears at 21 and is gone at 24', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Check presence at various seqs
    replay.seekSeq(20);
    expect(replay.resources.has(RUBBLE)).toBe(false);

    replay.seekSeq(21);
    expect(replay.resources.has(RUBBLE)).toBe(true);
    const areaResource = replay.resources.get(AREA)!;
    expect(areaResource.children).toContain(RUBBLE);

    replay.seekSeq(23);
    expect(replay.resources.has(RUBBLE)).toBe(true);
    const areaResource2 = replay.resources.get(AREA)!;
    expect(areaResource2.children).toContain(RUBBLE);

    replay.seekSeq(24);
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
    expect(replay.resources.get(PILLAR)!.n).toBe(2); // seq 3 applied last

    // Seek to seq 2 specifically
    replay.seekSeq(2);
    expect(replay.resources.get(PILLAR)!.n).toBe(1);
  });

  // Test 8: seekTime applies every entry up to and including the time
  it('seekTime applies every entry up to and including the time', () => {
    const replay = new Replay(throwSnapshot, throwEntries);

    // Before any entries with time 1041
    replay.seekTime(1041);
    expect(replay.seq).toBe(16); // seq 16 is the last entry <= time 1041

    // At time 1042 (should be at seq 22)
    replay.seekTime(1042);
    expect(replay.seq).toBe(22); // seq 22 is the last entry <= time 1042

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

    expect(replay.resources.get(PILLAR)!.n).toBe(9);
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
    expect(replay.nextEffectRoot()).toBe(2); // Index of seq 13, the intent that starts the throw tree

    // After seeking to effect root
    replay.seekIndex(2); // seq 13
    expect(replay.prevEffectRoot()).toBeNull();
    expect(replay.nextEffectRoot()).toBeNull();

    // After seeking to end
    replay.seekIndex(replay.length - 1);
    expect(replay.prevEffectRoot()).toBe(2); // Index of seq 13
    expect(replay.nextEffectRoot()).toBeNull();
  });

  it('effect roots: an intent starts a tree; so does an action a behaviour started, but not one an intent started', () => {
    const e = (seq: number, kind: string, cause: number | null, root: number) =>
      entry(seq, 1000 + seq, kind, { cause, root });
    const replay = new Replay(throwSnapshot, [
      e(11, 'intent', null, 11), // a tree with an effect: a root
      e(12, 'action-start', 11, 11), // started by the intent: not a root
      e(13, 'effect', 12, 11),
      e(14, 'intent', null, 14), // a tree without effects: not a root
      e(15, 'action-start', 14, 14),
      e(16, 'action-start', null, 16), // a behaviour's pick, with an effect: a root
      e(17, 'effect', 16, 16),
    ]);
    expect(replay.effectRoots).toEqual([0, 5]);
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

    replay.seekSeq(15);
    const entities = replay.areaEntities(AREA);
    expect(entities.map(r => r.id)).toEqual([PILLAR, ORBITER, PEBBLE]);
  });

  // Test 14: binary search optimizations
  describe('binary search optimizations', () => {
    // Create a large replay with 12000 entries for performance testing
    const createLargeReplay = () => {
      const entries = [];
      for (let i = 1; i <= 12000; i++) {
        entries.push({
          seq: i,
          area: 'area:test',
          time: 1000 + i / 10,
          kind: 'wait-ish',
          cause: null,
          root: i,
          resource: null,
          draws: [],
          changes: []
        });
      }
      const snapshot = {
        seq: 0,
        area: 'area:test',
        time: 1000,
        kind: 'snapshot',
        cause: null,
        root: 0,
        resource: null,
        draws: [],
        changes: []
      };
      return new Replay(snapshot, entries);
    };

    it('seekSeq finds correct indices for various values', () => {
      const replay = createLargeReplay();

      // Test first entry
      replay.seekSeq(1);
      expect(replay.index).toBe(0);

      // Test middle entry
      replay.seekSeq(6000);
      expect(replay.index).toBe(5999);

      // Test last entry
      replay.seekSeq(12000);
      expect(replay.index).toBe(11999);

      // Test below range
      replay.seekSeq(0);
      expect(replay.index).toBe(-1);

      // Test above range
      replay.seekSeq(15000);
      expect(replay.index).toBe(11999);

      // Test exact match for middle
      replay.seekSeq(6000);
      expect(replay.index).toBe(5999);
    });

    it('seekTime finds correct indices for various values', () => {
      const replay = createLargeReplay();

      // Test first entry (time = 1000 + 1/10 = 1000.1)
      replay.seekTime(1000.1);
      expect(replay.index).toBe(0);

      // Test middle entry (time = 1000 + 6000/10 = 1600)
      replay.seekTime(1600);
      expect(replay.index).toBe(5999);

      // Test last entry (time = 1000 + 12000/10 = 2200)
      replay.seekTime(2200);
      expect(replay.index).toBe(11999);

      // Test below range
      replay.seekTime(999);
      expect(replay.index).toBe(-1);

      // Test above range
      replay.seekTime(3000);
      expect(replay.index).toBe(11999);
    });

    it('prevEffectRoot and nextEffectRoot work with binary search', () => {
      // A small replay with effect roots: seqs 1 and 3 start trees with an effect, seq 5 does not
      const bare = (seq: number, kind: string, cause: number | null, root: number) =>
        entry(seq, 1000 + seq - 1, kind, { cause, root, area: 'area:test' });
      const entries = [
        bare(1, 'action-start', null, 1),
        bare(2, 'effect', 1, 1),
        bare(3, 'action-start', null, 3),
        bare(4, 'effect', 3, 3),
        bare(5, 'action-start', null, 5),
      ];
      const replay = new Replay(bare(0, 'snapshot', null, 0), entries);
      const roots = (index: number) => {
        replay.seekIndex(index);
        return [replay.prevEffectRoot(), replay.nextEffectRoot()];
      };

      // At index -1 (before any entries)
      expect([replay.prevEffectRoot(), replay.nextEffectRoot()]).toEqual([null, 0]);
      expect(roots(0)).toEqual([null, 2]); // at the effect root seq 1
      expect(roots(1)).toEqual([0, 2]);
      expect(roots(2)).toEqual([0, null]); // at the effect root seq 3
      expect(roots(4)).toEqual([2, null]); // at the last entry
    });
  });
});