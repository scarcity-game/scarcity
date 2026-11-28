package com.pessimisticit.scarcity.world.model.rules;

/**
 * An effect type (docs/action-types.md, "Effects" and "Effect types"): one
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

    /**
     * How the effect's log entry reads among the player's messages (docs/log.md, "Messages"),
     * from the area as it is before the effect runs. {@code source} is the entity whose own action
     * set the chain off. {@link LogTag#EVENT} by default.
     */
    default LogTag logTag(P params, AreaView areaView, String source) {
        return LogTag.EVENT;
    }

    /** A call of this effect with {@code params}, to trigger. */
    default EffectCall<P> call(P params) {
        return new EffectCall<>(this, params);
    }
}
