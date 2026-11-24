package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Item;

/** {@code create-item}: makes {@code item} (its id minted by the engine) as a child of the stash {@code parent}. */
public final class CreateItem implements Effect<CreateItem.Params> {

    public static final CreateItem INSTANCE = new CreateItem();

    private CreateItem() {
    }

    public record Params(@JsonProperty(required = true) String parent, @JsonProperty(required = true) Item item) {
        public Params {
            Check.text(parent, "parent");
            Check.required(item, "item");
        }
    }

    /** The new item's id. */
    public record Result(String item) {
    }

    @Override
    public String name() {
        return "create-item";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        ctx.result(new Result(ctx.createItem(params.parent(), params.item())));
    }
}
