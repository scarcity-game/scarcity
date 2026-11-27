package com.pessimisticit.scarcity.combat;

import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.model.rules.Damage;
import com.pessimisticit.scarcity.world.model.rules.Destroy;
import java.util.List;

/** The fighting rules (docs/action-types.md, {@code strike}), wired to each other and added to a catalogue. */
public final class Combat {

    private Combat() {
    }

    /** {@code base} with {@code strike} and {@code melee-attack}; a hit's damage kills at 0 hp (#450). */
    public static Catalogue addTo(Catalogue base) {
        MeleeAttack meleeAttack = new MeleeAttack(new Damage(Destroy.INSTANCE));
        return base.with(List.of(new Strike(meleeAttack)), List.of(meleeAttack));
    }

    /** {@link Catalogue#standard()} with the fighting rules: what services/world runs. */
    public static Catalogue standard() {
        return addTo(Catalogue.standard());
    }
}
