package com.pessimisticit.scarcity.world;

import java.util.List;

/** The production action and effect types, for tests that build a {@link Ticker} by hand. */
final class TestTypes {

    private TestTypes() {
    }

    static List<ActionType> actions() {
        return List.of(new OrbitStep(), new Wait(), new Throw(), new Fly(), new Decay());
    }

    static List<EffectType> effects() {
        return List.of(new CreateEffect(), new DestroyEffect(), new LineAttack(), new DamageEffect(), new BreakEffect());
    }
}
