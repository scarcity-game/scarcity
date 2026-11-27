package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Cell;

/** The {@code fill} action: at its end, triggers the {@code fill} effect on {@code params.cell}. */
public final class FillAction extends CellAction {

    public static final FillAction INSTANCE = new FillAction();

    private FillAction() {
        super("fill", Cell.Kind.FLOOR);
    }

    @Override
    EffectCall<?> call(String cell) {
        return Fill.INSTANCE.call(new Fill.Params(cell));
    }
}
