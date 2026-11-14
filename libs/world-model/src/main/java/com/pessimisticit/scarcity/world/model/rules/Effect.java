package com.pessimisticit.scarcity.world.model.rules;

/**
 * An effect type (docs/actions.md, "Effects" and "Effect types"): one
 * instant step that changes resources through its {@link EffectContext}.
 * Implementations are stateless.
 *
 * @param <P> the params record, written to the log as the effect's params
 */
public interface Effect<P extends Record> {

    /** The name in the log's {@code effect.type}. */
    String name();

    Class<P> params();

    void run(P params, EffectContext ctx);

    /** A call of this effect with {@code params}, to trigger. */
    default EffectCall<P> call(P params) {
        return new EffectCall<>(this, params);
    }
}
