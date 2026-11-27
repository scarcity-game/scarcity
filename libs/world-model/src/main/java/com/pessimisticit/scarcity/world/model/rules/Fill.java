package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Check;

/** {@code fill}: turns a floor cell into a wall (docs/action-types.md, "Effect types"). */
public final class Fill implements Effect<Fill.Params> {

    public static final Fill INSTANCE = new Fill();

    private Fill() {
    }

    public record Params(@JsonProperty(required = true) String cell) {
        public Params {
            Check.text(cell, "cell");
        }
    }

    @Override
    public String name() {
        return "fill";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        CellEdit.run(ctx, params.cell(), Cell.Kind.FLOOR, mesh -> mesh::close);
    }
}
