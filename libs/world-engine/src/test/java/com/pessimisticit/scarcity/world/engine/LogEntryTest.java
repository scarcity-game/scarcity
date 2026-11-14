package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Entry bodies in the shape of docs/log.md, and the snapshot entry. */
class LogEntryTest {

    private static Store example() {
        return new Store(Room.area(10.0))
                .put(PILLAR, Room.pillar())
                .put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat(Room.repeatWait(1.0)).withAction(new ActionInProgress("wait",
                        Room.object("{\"duration\":1.0}"), 10.0, 11.0, Timing.NONE, 15L, null)))
                .logFrom(20, Map.of(15L, 15L));
    }

    @Test
    void anIntentCarriesWhoWhatAndTheReportedPickVerbatim() {
        Intent intent = new Intent(ORBITER, Room.waitFor(2.0), Room.ORBITAL, null,
                Room.object("{\"draw\":{\"u\":0.42},\"note\":\"not checked\"}"));
        AdvanceResult r = Engine.advance(example().snapshot(), intent, new ScriptedRandom(), 20,
                Catalogue.standard());

        assertThat(r.log().getFirst().body()).isEqualTo(Room.object("""
                {"by":{"kind":"service","principal":"orbital"},
                 "intent":{"type":"wait","params":{"duration":2.0}},
                 "reported":{"draw":{"u":0.42},"note":"not checked"},
                 "draws":[],"changes":[]}"""));
    }

    @Test
    void effectAndActionBodiesHaveTheirDrawsResultsAndChanges() {
        AdvanceResult r = Engine.advance(example().snapshot(), Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.4).uniform(0.1), 20, Catalogue.standard());

        LogEntry damage = r.log().get(6);
        assertThat(Room.object(damage.body().toString())).isEqualTo(Room.object("""
                {"effect":{"type":"damage","params":{"target":"%s","mean":2.0,"sd":0.5,"min":0.5,"max":4.0}},
                 "result":{"hpBefore":100.0,"hpAfter":97.8},
                 "draws":[{"for":"damage","dist":"gaussian","mean":2.0,"sd":0.5,"truncate":{"min":0.5,"max":4.0},
                           "value":2.2}],
                 "changes":[{"id":"%s","type":"entity","op":"changed","version":2,
                             "state":{"kind":"fixed","name":"pillar","position":{"x":5.0,"y":4.0},"radius":0.5,
                                      "hp":97.8}}]}""".formatted(PILLAR, PILLAR)));
        LogEntry destroy = r.log().get(8);
        assertThat(destroy.body().get("changes").get(0)).isEqualTo(Room.object(
                "{\"id\":\"%s\",\"type\":\"entity\",\"op\":\"destroyed\"}".formatted(destroy.changes().getFirst().id())));
        LogEntry flyStart = r.log().get(4);
        assertThat(flyStart.body().get("changes").get(0).get("version").isNull()).isTrue();
        assertThat(flyStart.body().get("action").get("seq").asLong()).isEqualTo(24);
        assertThat(flyStart.body().has("result")).isFalse();
    }

    @Test
    void aSnapshotListsTheAreaAndEveryEntityWithItsVersion() {
        AreaSnapshot snapshot = example().snapshot();
        LogEntry entry = LogEntry.snapshot(19, snapshot);

        assertThat(entry.kind()).isEqualTo(LogEntry.Kind.SNAPSHOT);
        assertThat(entry.root()).isEqualTo(19);
        assertThat(entry.cause()).isNull();
        assertThat(entry.time()).isEqualTo(10.0);
        assertThat(entry.changes()).extracting(LogChange::id).containsExactly(Room.AREA, PILLAR, ORBITER, RAT);
        assertThat(entry.changes()).allMatch(c -> c.op() == LogChange.Op.SNAPSHOT);
        assertThat(entry.changes().getFirst().version()).isEqualTo(5L);
        assertThat(entry.body().get("changes").get(0).get("state").get("time").asDouble()).isEqualTo(10.0);
    }
}
