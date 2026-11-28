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

        assertThat(r.log().getFirst().bodyJson()).isEqualTo(Room.object("""
                {"category":"game","type":"intent","level":4,
                 "controller":{"kind":"service","principal":"orbital"},
                 "actionSpec":{"type":"wait","params":{"duration":2.0}},
                 "reported":{"draw":{"u":0.42},"note":"not checked"},
                 "draws":[],"changes":[]}"""));
    }

    @Test
    void everyEntryOfAThrowHasItsRulesTag() {
        AdvanceResult r = Engine.advance(example().snapshot(), Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1), 20, Catalogue.standard());

        assertThat(r.log()).extracting(e -> e.kind().wire() + " " + e.logTag().type() + " " + e.logTag().level())
                .containsExactly(
                        "intent intent 4",
                        "action-start attack 5", // the throw
                        "effect attack 5", // line-attack
                        "effect event 2", // create the pebble
                        "action-start movement 3", // its flight
                        "action-end movement 3",
                        "effect damage 5", // to the pillar, which no user plays
                        "effect event 2", // break
                        "effect event 2", // destroy the pebble, which has no hp: not a death
                        "effect event 2", // settle
                        "effect event 2", // create-item (the rubble)
                        "action-end wait 1", // the rat
                        "action-start wait 1",
                        "action-end attack 5", // the throw's recovery
                        "action-end wait 1",
                        "action-start wait 1");
    }

    @Test
    void effectAndActionBodiesHaveTheirDrawsResultsAndChanges() {
        AdvanceResult r = Engine.advance(example().snapshot(), Room.intent(Room.throwAt(5.0, 4.0, 0.1)),
                new ScriptedRandom().normal(0.0, 0.0, 0.0, 0.4).uniform(0.1), 20, Catalogue.standard());

        LogEntry damage = r.log().get(6);
        assertThat(Room.object(damage.bodyJson().toString())).isEqualTo(Room.object("""
                {"category":"game","type":"damage","level":5,
                 "loggedEffect":{"type":"damage","params":{"target":"%s","mean":2.0,"sd":0.5,"min":0.5,"max":4.0}},
                 "result":{"hpBefore":100.0,"hpAfter":97.8},
                 "draws":[{"for":"damage","dist":"gaussian","mean":2.0,"sd":0.5,"truncate":{"min":0.5,"max":4.0},
                           "value":2.2}],
                 "changes":[{"id":"%s","type":"entity","op":"changed","version":2,
                             "state":{"resourceKind":"entity","entityKind":"fixed","prototype":"pillar","id":"%s","version":2,"name":"pillar",
                                      "position":{"x":5.0,"y":4.0},"radius":0.5,
                                      "hp":97.8,"overlap":false,"damageable":true}}]}""".formatted(PILLAR, PILLAR, PILLAR)));
        LogEntry destroy = r.log().get(8);
        assertThat(destroy.bodyJson().get("changes").get(0)).isEqualTo(Room.object(
                "{\"id\":\"%s\",\"type\":\"entity\",\"op\":\"destroyed\"}".formatted(destroy.changes().getFirst().id())));
        LogEntry flyStart = r.log().get(4);
        assertThat(flyStart.bodyJson().get("changes").get(0).get("version").isNull()).isTrue();
        assertThat(flyStart.bodyJson().get("actionInProgress").get("seq").asLong()).isEqualTo(24);
        assertThat(flyStart.bodyJson().has("result")).isFalse();
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
        assertThat(entry.bodyJson().get("changes").get(0).get("state").get("time").asDouble()).isEqualTo(10.0);
    }
}
