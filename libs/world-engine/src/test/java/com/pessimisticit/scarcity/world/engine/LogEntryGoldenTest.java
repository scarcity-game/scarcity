package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One log entry of each kind (docs/log.md), its body byte for byte as world-db
 * writes it to the database ({@link Golden}). A changed line is a contract
 * change (#246).
 */
class LogEntryGoldenTest {

    private static final String PEBBLE = "entity:00000000-0000-4000-8000-00000000000e";

    private static ActionInProgress wait(double start, double end, long seq, Draw draw) {
        return new ActionInProgress("wait", Room.object("{\"duration\":" + (end - start) + "}"), start, end,
                Timing.END, seq, draw);
    }

    private static List<LogEntry> oneOfEachKind() {
        Store store = new Store(Room.area(10.0)).put(PILLAR, Room.pillar()).put(ORBITER, Room.orbiter())
                .put(RAT, Room.rat(Room.repeatWait(1.0)));
        Draw.Categorical pick = new Draw.Categorical(List.of(2.0, 1.0), 0.6180339887, 1);
        Draw.Gaussian hit = new Draw.Gaussian(2.0, 0.5, new Draw.Truncate(0.5, 4.0), 2.2);
        Map<String, Long> versions = Map.of(PILLAR, 2L, RAT, 3L, ORBITER, 4L);
        return List.of(
                LogEntry.snapshot(1, store.snapshot()),
                LogEntry.intent(new LogEntry.Head(2, 10.0, null, 2), ORBITER, Room.ORBITAL, Room.waitFor(1.5),
                        Room.object("{\"draw\":{\"u\":0.42},\"note\":\"not checked\"}")),
                LogEntry.actionStart(new LogEntry.Head(3, 10.0, 2L, 2), ORBITER, wait(10.0, 11.5, 3, null),
                        List.of(), null, Room.orbiter()).withVersions(versions::get),
                LogEntry.actionStart(new LogEntry.Head(4, 10.0, null, 4), RAT, wait(10.0, 11.0, 4, pick),
                        List.of(new LoggedDraw("behaviour", pick)), null, Room.rat(Room.repeatWait(1.0)))
                        .withVersions(versions::get),
                LogEntry.loggedEffect(new LogEntry.Head(5, 10.5, 3L, 2),
                        new LoggedEffect("damage", Room.object(
                                "{\"target\":\"" + PILLAR + "\",\"mean\":2.0,\"sd\":0.5,\"min\":0.5,\"max\":4.0}")),
                        Room.object("{\"hpBefore\":100.0,\"hpAfter\":97.8}"),
                        List.of(new LoggedDraw("damage", hit)),
                        List.of(LogChange.changed(PILLAR, Room.pillar()),
                                LogChange.created(PEBBLE, Room.state("""
                                        {"resourceKind":"entity","entityKind":"fixed","prototype":"pebble",
                                         "name":"pebble","position":{"x":5.0,"y":4.0},"radius":0.02}"""), Room.AREA),
                                LogChange.destroyed(RAT))).withVersions(versions::get),
                LogEntry.cancelled(new LogEntry.Head(6, 10.5, 5L, 2), RAT, wait(10.0, 11.0, 4, pick)),
                LogEntry.actionEnd(new LogEntry.Head(7, 11.5, 3L, 2), ORBITER, wait(10.0, 11.5, 3, null),
                        Room.object("{\"waited\":1.5}"), Room.orbiter()).withVersions(versions::get),
                LogEntry.queue(new LogEntry.Head(8, 11.5, null, 8)),
                LogEntry.catchUp(new LogEntry.Head(9, 1011.5, null, 9), 11.5, 1011.5));
    }

    @Test
    void everyKindOfEntryHasTheBodyOfItsGolden() {
        List<LogEntry> entries = oneOfEachKind();
        List<String> lines = entries.stream()
                .map(e -> "{\"kind\":\"" + e.kind().wire() + "\",\"body\":" + e.bodyJson().toString() + "}")
                .toList();

        Golden.assertLines("log-entries.jsonl", lines);
        assertThat(entries.stream().map(LogEntry::kind).distinct()).containsExactlyInAnyOrder(LogEntry.Kind.values());
    }
}
