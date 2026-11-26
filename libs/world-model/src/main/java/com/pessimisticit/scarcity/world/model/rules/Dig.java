package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Check;

/** {@code dig}: turns a wall cell into floor (docs/action-types.md, "Effect types"). */
public final class Dig implements Effect<Dig.Params> {

    public static final Dig INSTANCE = new Dig();

    private Dig() {
    }

    public record Params(@JsonProperty(required = true) String cell) {
        public Params {
            Check.text(cell, "cell");
        }
    }

    @Override
    public String name() {
        return "dig";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        CellEdit.run(ctx, params.cell(), Cell.Kind.WALL, mesh -> mesh::open);
    }
}
