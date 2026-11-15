package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.orbital.OrbitalProperties.WaitSettings;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@code wait} for the configured duration. */
@Component
public final class WaitChoice implements Choice {

    private final Wait.Params params;

    @Autowired
    public WaitChoice(OrbitalProperties orbitalProperties) {
        this(orbitalProperties.waitSettings());
    }

    WaitChoice(WaitSettings waitSettings) {
        this.params = new Wait.Params(waitSettings.duration());
    }

    @Override
    public String name() {
        return Wait.INSTANCE.name();
    }

    @Override
    public ActionSpec plan(Entity self, World world) {
        return Wait.INSTANCE.spec(params);
    }
}
