package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.TurnDelay;
import com.pessimisticit.scarcity.controller.TurnFrame;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The diver's wall-clock pause (#171): {@code diver.arrival-pause} on an entity's first turn in
 * an area, none on its later turns in that area, again after it reaches another area.
 */
@Component
public class ArrivalPause implements TurnDelay {

    private final Duration pause;
    /** entity → the area its last pause was for. */
    private final Map<String, String> pausedIn;

    public ArrivalPause(DiverProperties diverProperties) {
        this.pause = diverProperties.arrivalPause();
        this.pausedIn = new HashMap<>();
    }

    @Override
    public Duration before(TurnFrame turn) {
        boolean arrived = !turn.area().equals(pausedIn.put(turn.entity(), turn.area()));
        return arrived ? pause : Duration.ZERO;
    }
}
