package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.engine.IntentRejected.Reason;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** An intent that can't start is rejected with its reason; nothing happens and no draw is made. */
class RejectionTest {

    private static AreaSnapshot snapshot() {
        return new Store(Room.area(10.0)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat(Room.repeatWait(1.0))).snapshot();
    }

    private static void rejected(AreaSnapshot snap, Intent intent, Reason reason, String message) {
        ThrowingCallable advance = () -> Engine.advance(snap, intent, new ScriptedRandom(), 1, Catalogue.standard());
        assertThatThrownBy(advance)
                .isInstanceOfSatisfying(IntentRejected.class, e -> assertThat(e.reason()).isEqualTo(reason))
                .hasMessage(message);
    }

    @Test
    void anUnknownEntity() {
        rejected(snapshot(), Intent.of("entity:00000000-0000-4000-8000-0000000000ff", Room.waitFor(1.0),
                Room.ORBITAL), Reason.UNKNOWN_ENTITY,
                "entity: no entity entity:00000000-0000-4000-8000-0000000000ff in " + Room.AREA);
    }

    @Test
    void anAreaWithNoControlledEntityDoesNotAdvance() {
        AreaSnapshot npcsOnly = new Store(Room.area(10.0)).put(RAT, Room.rat(Room.repeatWait(1.0))).snapshot();
        rejected(npcsOnly, Intent.of(RAT, Room.waitFor(1.0), Room.ORBITAL), Reason.NOT_CONTROLLED,
                "entity: " + RAT + " has no controller");
        rejected(snapshot(), Intent.of(PILLAR, Room.waitFor(1.0), Room.ORBITAL), Reason.NOT_CONTROLLED,
                "entity: " + PILLAR + " has no controller");
    }

    @Test
    void aStaleVersion() {
        rejected(snapshot(), new Intent(ORBITER, Room.waitFor(1.0), Room.ORBITAL, 7L, null), Reason.STALE,
                "ifVersion: " + ORBITER + " is at version 1, not 7");
    }

    @Test
    void aBusyEntity() {
        AreaSnapshot busy = new Store(Room.area(10.0)).put(ORBITER, Room.orbiter().withAction(new ActionInProgress(
                "wait", Room.object("{\"duration\":2.0}"), 9.0, 11.0, Timing.NONE, 3L, null))).snapshot();
        rejected(busy, Room.intent(Room.waitFor(1.0)), Reason.BUSY,
                "entity: " + ORBITER + " already has an action (wait until 11.0)");
    }

    @Test
    void everyStartRuleOfTheCatalogue() {
        rejected(snapshot(), Room.intent(new ActionSpec("fly", Room.object("{}"))), Reason.INVALID,
                "fly: not in the entity's actions [step, wait, throw]");
        rejected(snapshot(), Room.intent(Room.stepTo(11.0, 4.0)), Reason.INVALID,
                "step: to: Position[x=11.0, y=4.0] is outside the area (10.0 x 8.0)");
        rejected(snapshot(), Room.intent(new ActionSpec("wait", Room.object("{}"))),
                Reason.INVALID, "wait params at 'duration': duration: required");
    }
}
