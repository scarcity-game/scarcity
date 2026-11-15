package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.rules.Catalogue;
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
}
