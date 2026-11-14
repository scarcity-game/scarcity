package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Snapshots, pruning and the roots {@link AreaRepository#load} reads back (docs/log.md, "Snapshots and pruning"). */
class SnapshotTest extends DbTest {

    private final Room room = new Room(store, tx);

    private List<JsonNode> log(String kind) {
        List<JsonNode> out = new ArrayList<>();
        reader.read(room.area, 0, AreaLogReader.MAX_LIMIT, kind).get("entries").forEach(out::add);
        return out;
    }

    @Test
    void theFirstAdvanceStartsTheLogWithASnapshotAtTheVersionsItLoaded() {
        AreaSnapshot before = tx.execute(s -> repo.load(room.area));
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);

        JsonNode snapshot = log(null).getFirst();
        assertThat(snapshot.get("kind").asText()).isEqualTo("snapshot");
        assertThat(snapshot.get("seq").asLong()).isEqualTo(1);
        assertThat(snapshot.get("root").asLong()).isEqualTo(1);
        assertThat(snapshot.get("time").asDouble()).isZero();
        JsonNode changes = snapshot.get("changes");
        assertThat(changes).hasSize(4);
        assertThat(changes.get(0).get("id").asText()).isEqualTo(room.area);
        assertThat(changes.get(0).get("version").asLong()).isEqualTo(before.area().version());
        for (JsonNode c : changes) {
            assertThat(c.get("op").asText()).isEqualTo("snapshot");
        }
        assertThat(log(null).get(1).get("kind").asText()).isEqualTo("intent");
    }

    @Test
    void aSnapshotIsWrittenOncePerIntervalAndOnlyByAnAdvance() {
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0.plus(Duration.ofMinutes(59)));
        assertThat(log("snapshot")).hasSize(1);

        AdvanceResult third = advance(room.area, room.waitFor(1.0), new ScriptedRandom(),
                T0.plus(Duration.ofMinutes(60)));
        List<JsonNode> snapshots = log("snapshot");
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(1).get("seq").asLong()).isEqualTo(third.log().getFirst().seq() - 1);
        assertThat(snapshots.get(1).get("time").asDouble()).isEqualTo(2.0);
        assertThat(JDBC.queryForObject("SELECT last_snapshot_at FROM area_log_head WHERE area_id = ?",
                java.sql.Timestamp.class, room.area).toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
    }

    @Test
    void entriesBeforeTheNewestSnapshotOlderThanTheRetentionArePruned() {
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        Instant second = T0.plus(Duration.ofHours(2));
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), second);
        long secondSnapshot = log("snapshot").getLast().get("seq").asLong();
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), second.plus(Duration.ofHours(23)));
        assertThat(log(null).getFirst().get("seq").asLong()).as("nothing older than 24 h yet").isEqualTo(1);

        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), second.plus(Duration.ofHours(25)));
        JsonNode page = reader.read(room.area, 0, 1, null);
        assertThat(page.get("first").asLong()).isEqualTo(secondSnapshot);
        assertThat(page.get("entries").get(0).get("kind").asText()).isEqualTo("snapshot");
    }

    @Test
    void loadGivesTheRootOfEachActionInProgressAndAPrunedOneIsMissing() {
        AdvanceResult thrown = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        String rubble = thrown.created().getFirst();
        long rubbleSeq = thrown.write(rubble).orElseThrow().state().action().seq();
        long ratSeq = thrown.write(room.rat).orElseThrow().state().action().seq();

        AreaSnapshot loaded = tx.execute(s -> repo.load(room.area));
        assertThat(loaded.roots()).containsEntry(rubbleSeq, thrown.log().getFirst().seq())
                .containsEntry(ratSeq, ratSeq).hasSize(2);
        assertThat(loaded.entities()).hasSize(4);
        assertThat(loaded.area().version()).isEqualTo(thrown.areaVersion());
        assertThat(loaded.area().state()).isEqualTo(thrown.area());

        JDBC.update("DELETE FROM area_log WHERE area_id = ? AND seq = ?", room.area, rubbleSeq);
        assertThat(tx.execute(s -> repo.load(room.area)).roots()).doesNotContainKey(rubbleSeq);
    }
}
