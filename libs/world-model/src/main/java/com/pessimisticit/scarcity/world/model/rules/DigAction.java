package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Cell;

/** The {@code dig} action: at its end, triggers the {@code dig} effect on {@code params.cell}. */
public final class DigAction extends CellAction {

    public static final DigAction INSTANCE = new DigAction();

    private DigAction() {
        super("dig", Cell.Kind.WALL);
    }

    @Override
    EffectCall<?> call(String cell) {
        return Dig.INSTANCE.call(new Dig.Params(cell));
    }
}
