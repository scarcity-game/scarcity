package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The advance (docs/actions.md, "Game time"), in memory: no database, no
 * clock. The caller loads the snapshot under the area's lock, commits the
 * result in one transaction, and maps a rejection to its status.
 */
public final class Engine {

    /** More events and effects than this in one advance is a rule that never stops (docs/action-types.md, "Effects"). */
    public static final int MAX_STEPS = 100_000;

    private Engine() {
    }

    /**
     * Starts the intent's action at the area's {@code time} and runs the
     * area until a controlled entity is idle again.
     *
     * @param rng the area's generator: every draw and created id comes from it
     * @param nextSeq the seq of the first entry (the {@code intent})
     * @throws IntentRejected if the intent can't start; nothing happened
     * @throws RunawayAdvance after {@link #MAX_STEPS}
     */
    public static AdvanceResult advance(AreaSnapshot snapshot, Intent intent, RandomGenerator rng, long nextSeq,
            Catalogue catalogue) {
        return new Advance(snapshot, rng, nextSeq, catalogue).areaRun(intent);
    }

    /**
     * Runs an area that is behind the game, with no intent, up to game time {@code until}
     * (docs/actions.md, "Crossing areas"): a first jump of the area's time when it is more than
     * 500 behind, then its events up to {@code until}, whoever is idle.
     *
     * @param rng the area's generator
     * @param nextSeq the seq of the first entry
     * @return empty when nothing happened: the area is already at {@code until}
     * @throws RunawayAdvance after {@link #MAX_STEPS}
     */
    public static Optional<AdvanceResult> warmUp(AreaSnapshot snapshot, double until, RandomGenerator rng,
            long nextSeq, Catalogue catalogue) {
        return new Advance(snapshot, rng, nextSeq, catalogue).warmUp(until);
    }

    /**
     * Writes the queue into an area whose stored one is missing or wrong (a controlled idle
     * entity that is in no {@code activeEntity} or {@code pending}), one {@code queue} entry and no
     * game time. A new entry is queued from the area's {@code time}.
     *
     * @param nextSeq the seq of the {@code queue} entry
     * @return empty when the stored queue is already right: nothing to write
     */
    public static Optional<AdvanceResult> refresh(AreaSnapshot snapshot, long nextSeq, Catalogue catalogue) {
        return new Advance(snapshot, null, nextSeq, catalogue).refresh();
    }
}
