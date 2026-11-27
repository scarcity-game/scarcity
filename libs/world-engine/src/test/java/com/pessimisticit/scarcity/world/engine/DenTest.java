package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.engine.LogEntry.Kind;
import com.pessimisticit.scarcity.world.model.Behaviour;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Spawn;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A den under the real engine: its cycle of waiting and making, and its cap (#453). */
class DenTest {

    private static final String DEN = "entity:00000000-0000-4000-8000-0000000000d1";

    private static Store room() {
        Spawn.Params spawn = new Spawn.Params(List.of(new Spawn.Choice(1.0, "mouse")), 1, new Controller.Service("brawler"),
                new Spawn.Interval(3.0, 0.5, 1.0, 5.0), null);
        Entity den = new EntityTemplate("den").at(new Position(2.0, 4.0)).withBehaviour(new Behaviour.Repeat(Spawn.specFor(spawn)));
        return Room.walled(10.0).put(ORBITER, Room.orbiter()).put(DEN, den);
    }

    @Test
    void aDenMakesOneMobAfterItsWait() {
        Store store = room();
        // the interval, then radius, mass, speed and four attributes, then the next cycle's interval; the pick and the spot are uniform
        ScriptedRandom random = new ScriptedRandom().uniform(0.0, 0.0).normal(0, 0, 0, 0, 0, 0, 0, 0, 0);
        AdvanceResult r = store.advance(Room.intent(Room.waitFor(7.0)), random);

        List<LogEntry> effects = r.log().stream().filter(e -> e.kind() == Kind.EFFECT).toList();
        assertThat(effects).extracting(e -> e.loggedEffect().type())
                .containsExactly("spawn-wait", "spawn-make", "create", "spawn-wait");
        assertThat(effects.get(0).draws()).extracting(LoggedDraw::forWhat).containsExactly("interval");
        assertThat(effects.get(1).draws()).extracting(LoggedDraw::forWhat).containsExactly("pick", "radius", "spot",
                "mass", "speed", "level", "strength", "dexterity", "intelligence");
        assertThat(effects.get(0).result().get("interval").asDouble()).isEqualTo(3.0);

        List<Entity> mobs = store.entities().values().stream().filter(e -> DEN.equals(e.spawner())).toList();
        assertThat(mobs).hasSize(1);
        assertThat(mobs.get(0).controller()).isEqualTo(new Controller.Service("brawler"));
        assertThat(mobs.get(0).position().distanceTo(new Position(2.0, 4.0))).isGreaterThan(0.5);
        assertThat(random.exhausted()).isTrue();
        // the game stops there: the new mob is a controlled entity and waits for its controller's turn
        assertThat(r.area().time()).isEqualTo(13.0);
    }
}
