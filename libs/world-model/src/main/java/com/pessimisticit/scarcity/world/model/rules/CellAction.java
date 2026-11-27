package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;

/**
 * What {@code dig} and {@code fill} share: an action of {@link #DURATION} seconds on one cell that, at
 * its end, triggers the effect of the same name. A way to change the level, for tests and debugging; the
 * entity needs the type in its {@code actions}.
 */
abstract class CellAction implements Action<CellAction.Params> {

    /** Game seconds the action takes. */
    static final double DURATION = 1.0;

    public record Params(@JsonProperty(required = true) String cell) {
        public Params {
            Check.text(cell, "cell");
        }
    }

    private final String name;
    private final Cell.Kind from;

    CellAction(String name, Cell.Kind from) {
        this.name = name;
        this.from = from;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public Timing timing() {
        return Timing.END;
    }

    @Override
    public double duration(Params params, Entity self) {
        return DURATION;
    }

    @Override
    public void validate(Params params, Actor self) {
        Cell cell = self.areaView().cells().get(params.cell());
        if (cell == null) {
            throw new InvalidAction("cell: no " + params.cell() + " in the area");
        }
        if (cell.kind() != from) {
            throw new InvalidAction("cell: " + params.cell() + " is a " + cell.kind().name().toLowerCase() + ", " + name
                    + " needs a " + from.name().toLowerCase());
        }
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(call(params.cell()));
    }

    abstract EffectCall<?> call(String cell);
}
