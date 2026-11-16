package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.engine.LogChange;
import com.pessimisticit.scarcity.world.engine.LogEntry;
import com.pessimisticit.scarcity.world.engine.Write;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** {@link AreaRepository#commit} of real engine results: what lands in the tables and on the channel. */
class CommitTest extends DbTest {

    private final Room room;

    CommitTest() {
        this.room = new Room(resourceStore, tx);
    }

    private long areaVersion() {
        return resourceStore.get(room.area).orElseThrow().version();
    }

    private List<JsonNode> log() {
        return list(areaLogReader.read(room.area, 0, AreaLogReader.MAX_LIMIT, null).get("entries"));
    }

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    /** The first entity the advance created: the pebble of a throw. */
    private static String firstCreated(AdvanceResult r) {
        return r.log().stream().flatMap(e -> e.changes().stream())
                .filter(c -> c.op() == LogChange.Op.CREATED).findFirst().orElseThrow().id();
    }

    private List<String> sorted(String... ids) {
        return List.of(ids).stream().sorted().toList();
    }

    private static List<String> concat(String first, List<String> middle, String last) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(middle);
        out.add(last);
        return out;
    }

    @Test
    void anAdvanceWritesTheAreaOnceWhateverItCreatesAndDestroys() {
        long before = areaVersion();
        AdvanceResult thrown = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        assertThat(thrown.created()).hasSize(1);
        assertThat(areaVersion()).isEqualTo(before + 1).isEqualTo(thrown.areaVersion());

        AdvanceResult waited = advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        assertThat(waited.created()).isEmpty();
        assertThat(waited.destroyed()).isEmpty();
        assertThat(areaVersion()).isEqualTo(before + 2);
    }

    @Test
    void theBatchIsTheAreaThenChangedThenCreatedThenDestroyedWithTheLogsLastSeq() throws Exception {
        AdvanceResult thrown = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        AdvanceResult decayed = advance(room.area, room.waitFor(20.0), new ScriptedRandom(), T0);
        List<ChangeBatch> batches = batches(room.area, 2);

        assertThat(batches).hasSize(2);
        String rubble = thrown.created().getFirst();
        ChangeBatch first = batches.get(0);
        assertThat(first.traceparent()).isEqualTo(TRACEPARENT);
        assertThat(first.logSeq()).isEqualTo(thrown.lastSeq());
        assertThat(ids(first)).isEqualTo(concat(room.area, sorted(room.orbiter, room.pillar, room.rat), rubble));
        assertThat(first.changes().getFirst().children()).containsExactly(room.pillar, room.orbiter, room.rat,
                rubble);
        assertThat(first.changes().get(4).version()).isEqualTo(1);
        for (Change c : first.changes().subList(1, 4)) {
            assertThat(c.version()).isEqualTo(thrown.write(c.id()).orElseThrow().version());
            assertThat(c.children()).isNull();
        }

        ChangeBatch second = batches.get(1);
        assertThat(second.logSeq()).isEqualTo(decayed.lastSeq())
                .isEqualTo(areaLogReader.read(room.area, 0, 1, null).get("last").asLong());
        assertThat(ids(second)).isEqualTo(concat(room.area, sorted(room.orbiter, room.rat), rubble));
        assertThat(second.changes().getFirst().version()).isEqualTo(thrown.areaVersion() + 1);
        assertThat(second.changes().getFirst().children()).containsExactly(room.pillar, room.orbiter, room.rat);
        assertThat(second.changes().getLast().deleted()).isTrue();
        assertThat(second.changes().getLast().patch()).isNull();
    }

    private static List<String> ids(ChangeBatch b) {
        return b.changes().stream().map(Change::id).toList();
    }

    @Test
    void eachPatchIsTheFullStateTheStoreNowHolds() throws Exception {
        advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        for (Change c : batches(room.area, 1).getFirst().changes()) {
            Resource stored = resourceStore.get(c.id()).orElseThrow();
            assertThat(c.patch()).as(c.id()).isEqualTo(stored.state());
            assertThat(c.version()).as(c.id()).isEqualTo(stored.version());
        }
    }

    @Test
    void aPebbleCreatedAndDestroyedInOneAdvanceIsNeverWrittenAndLogsNoVersion() {
        AdvanceResult r = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        String pebble = firstCreated(r);

        assertThat(resourceStore.exists(pebble)).isFalse();
        assertThat(r.write(pebble)).isEmpty();
        List<JsonNode> about = log().stream()
                .flatMap(e -> list(e.get("changes")).stream())
                .filter(c -> c.get("id").asText().equals(pebble))
                .toList();
        assertThat(about.getFirst().get("op").asText()).isEqualTo("created");
        assertThat(about.getLast().get("op").asText()).isEqualTo("destroyed");
        assertThat(about.subList(0, about.size() - 1)).hasSizeGreaterThan(1)
                .allMatch(c -> c.get("version").isNull());
    }

    @Test
    void everyLoggedVersionIsTheOneTheStoreWrote() {
        AdvanceResult r = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        for (JsonNode e : log()) {
            for (JsonNode c : list(e.get("changes"))) {
                JsonNode v = c.get("version");
                if (v != null && !v.isNull() && !"snapshot".equals(c.get("op").asText())) {
                    Write w = r.write(c.get("id").asText()).orElseThrow();
                    assertThat(v.asLong()).as(e.get("seq") + " " + c.get("id")).isEqualTo(w.version());
                    assertThat(resourceStore.get(w.id()).orElseThrow().version()).isEqualTo(w.version());
                }
            }
        }
    }

    @Test
    void theEntriesAreStoredInTheContractShapeWithGaplessSeq() {
        AdvanceResult r = advance(room.area, room.throwAtPillar(), Room.hitAndBreak(), T0);
        List<JsonNode> log = log();

        assertThat(log).hasSize(r.log().size() + 1);
        for (int i = 0; i < log.size(); i++) {
            assertThat(log.get(i).get("seq").asLong()).isEqualTo(i + 1);
            assertThat(log.get(i).get("area").asText()).isEqualTo(room.area);
            assertThat(log.get(i).get("wallTime").asText()).isEqualTo(T0.toString());
        }
        JsonNode intent = log.get(1);
        LogEntry engine = r.log().getFirst();
        assertThat(intent.get("kind").asText()).isEqualTo("intent");
        assertThat(intent.get("cause").isNull()).isTrue();
        assertThat(intent.get("resource").asText()).isEqualTo(room.orbiter);
        assertThat(intent.get("controller")).isEqualTo(engine.bodyJson().get("controller"));
        assertThat(intent.get("time").asDouble()).isEqualTo(engine.time());
        JsonNode effect = log.stream().filter(e -> e.get("kind").asText().equals("effect")).findFirst().orElseThrow();
        assertThat(effect.get("resource").isNull()).isTrue();
        assertThat(effect.get("loggedEffect").get("type").asText()).isEqualTo("line-attack");
        assertThat(effect.get("draws")).hasSize(1);
    }

    @Test
    void aRolledBackAdvanceLeavesNoRowsNoHeadAndPublishesNothing() throws Exception {
        long before = areaVersion();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            AreaHead head = areaLock.begin(room.area, T0);
            AreaSnapshot snapshot = areaRepository.load(room.area);
            AdvanceResult r = Engine.advance(snapshot, room.throwAtPillar(), Room.hitAndBreak(), head.nextSeq(),
                    Catalogue.standard());
            areaRepository.commit(head, snapshot, r, TRACEPARENT);
            throw new IllegalStateException("the caller fails after the commit");
        })).hasMessage("the caller fails after the commit");

        assertThat(areaVersion()).isEqualTo(before);
        assertThat(resourceStore.children(room.area, ResourceType.ENTITY, 0, 10).total()).isEqualTo(3);
        assertThat(log()).isEmpty();
        assertThat(JDBC.queryForList("SELECT 1 FROM area_log_head WHERE area_id = ?", room.area)).isEmpty();

        AdvanceResult committed = advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        List<ChangeBatch> batches = batches(room.area, 1);
        assertThat(batches).hasSize(1);
        assertThat(batches.getFirst().logSeq()).isEqualTo(committed.lastSeq());
        assertThat(committed.log().getFirst().seq()).isEqualTo(2);
    }

    @Test
    void aWriteWithoutTheLockFailsTheCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            AreaHead head = areaLock.begin(room.area, T0);
            AreaSnapshot snapshot = areaRepository.load(room.area);
            AdvanceResult r = Engine.advance(snapshot, room.waitFor(1.0), new ScriptedRandom(), head.nextSeq(),
                    Catalogue.standard());
            resourceStore.updateState(room.rat, resourceStore.get(room.rat).orElseThrow().state());
            areaRepository.commit(head, snapshot, r, TRACEPARENT);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(room.rat + ": the advance expected version 2 but the store wrote 3");
        assertThat(log()).isEmpty();
    }

    @Test
    void aResultThatDoesNotFollowTheHeadIsRefused() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            AreaHead head = areaLock.begin(room.area, T0);
            AreaSnapshot snapshot = areaRepository.load(room.area);
            AdvanceResult r = Engine.advance(snapshot, room.waitFor(1.0), new ScriptedRandom(), head.lastSeq() + 1,
                    Catalogue.standard());
            areaRepository.commit(head, snapshot, r, TRACEPARENT);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the advance's log starts at 1, the head's next seq is 2");
    }
}
