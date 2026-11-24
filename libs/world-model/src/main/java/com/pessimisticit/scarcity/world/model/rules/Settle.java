package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stash;
import java.util.Map;
import java.util.Optional;

/**
 * {@code settle}: puts an item to rest at {@code at} (docs/resources.md, "Items and stashes"). It
 * joins the nearest stash whose position is within {@link Stash#joinDistance} (twice its radius) of {@code at}, ties by id;
 * with none, a new stash is made at {@code at}. Then the item {@code stack}s onto a like item in the
 * stash, or is made there by {@code create-item}.
 */
public final class Settle implements Effect<Settle.Params> {

    private final Effect<CreateItem.Params> createItem;
    private final Effect<Stack.Params> stack;

    public Settle(Effect<CreateItem.Params> createItem, Effect<Stack.Params> stack) {
        this.createItem = createItem;
        this.stack = stack;
    }

    /** {@code item} is a typed item without an id. */
    public record Params(@JsonProperty(required = true) Position at, @JsonProperty(required = true) Item item) {
        public Params {
            Check.required(at, "at");
            Check.required(item, "item");
        }
    }

    /** The stash it rests in, and whether that stash was made for it. */
    public record Result(String stash, boolean newStash) {
    }

    @Override
    public String name() {
        return "settle";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Optional<String> near = nearest(ctx, params.at());
        String stashId = near.orElseGet(() -> ctx.create(ctx.areaId(), Stash.at(params.at())));
        ctx.result(new Result(stashId, near.isEmpty()));
        Optional<String> like = ctx.itemsIn(stashId).stream()
                .filter(id -> ctx.peekItem(id).likes(params.item()))
                .findFirst();
        if (like.isPresent()) {
            ctx.trigger(stack.call(new Stack.Params(like.get(), params.item())));
        } else {
            ctx.trigger(createItem.call(new CreateItem.Params(stashId, params.item())));
        }
    }

    /** The nearest stash within its join distance of {@code at}; entities are by id, so a tie goes to the lower. */
    private static Optional<String> nearest(AreaView view, Position at) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Entity> e : view.entities().entrySet()) {
            if (e.getValue() instanceof Stash stash) {
                double d = stash.position().distanceTo(at);
                if (d <= stash.joinDistance() && d < bestDistance) {
                    best = e.getKey();
                    bestDistance = d;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
