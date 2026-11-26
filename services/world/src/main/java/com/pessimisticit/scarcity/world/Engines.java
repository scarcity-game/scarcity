package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.engine.Intent;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * The engine as world runs it: the rules' catalogue, the service's one random source, and the
 * intelligence services deciding their turns inside each advance ({@link IntelligenceDecider}).
 */
@Component
class Engines {

    private final Catalogue catalogue;
    private final RandomGenerator rng;
    private final IntelligenceDecider intelligenceDecider;

    Engines(Catalogue catalogue, RandomGenerator rng, IntelligenceDecider intelligenceDecider) {
        this.catalogue = catalogue;
        this.rng = rng;
        this.intelligenceDecider = intelligenceDecider;
    }

    /** {@link Engine#advance}, through every intelligence service's turn to the next player's or npc's. */
    AdvanceResult advance(AreaSnapshot snapshot, Intent intent, long nextSeq) {
        return Engine.advance(snapshot, intent, rng, nextSeq, catalogue, intelligenceDecider.forAdvance());
    }

    /** {@link Engine#warmUp}, its mobs' turns decided inside it: the whole warm-up in one advance. */
    Optional<AdvanceResult> warmUp(AreaSnapshot snapshot, double until, long nextSeq) {
        return Engine.warmUp(snapshot, until, rng, nextSeq, catalogue, intelligenceDecider.forAdvance());
    }

    /** {@link Engine#refresh}. */
    Optional<AdvanceResult> refresh(AreaSnapshot snapshot, long nextSeq) {
        return Engine.refresh(snapshot, nextSeq, catalogue);
    }
}
