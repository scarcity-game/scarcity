package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Item;

/**
 * {@code stack}: adds {@code item}'s mass, and its count for a {@code count} item, to the like item
 * {@code target} ({@link Item#combine}). {@code item} is never made.
 */
public final class Stack implements Effect<Stack.Params> {

    public static final Stack INSTANCE = new Stack();

    private Stack() {
    }

    public record Params(@JsonProperty(required = true) String target, @JsonProperty(required = true) Item item) {
        public Params {
            Check.text(target, "target");
            Check.required(item, "item");
        }
    }

    /** Before and after; the counts are null for a {@code mass} item. */
    public record Result(Double countBefore, Double countAfter, double massBefore, double massAfter) {
    }

    @Override
    public String name() {
        return "stack";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Item before = ctx.peekItem(params.target());
        Item after = before.combine(params.item());
        ctx.updateItem(params.target(), after);
        ctx.result(new Result(before.count(), after.count(), before.mass(), after.mass()));
    }
}
