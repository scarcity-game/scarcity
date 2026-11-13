package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;

class AreaLogTest extends LogTestBase {

    private static final List<String> ALL = List.of(WorldIds.AREA, WorldIds.PILLAR, WorldIds.ORBITER);

    @Test
    void theFirstTickWritesAnchorSnapshotAndStart() throws Exception {
        JsonNode seededOrbiter = store.get(WorldIds.ORBITER).orElseThrow().state();
        JsonNode seededPillar = store.get(WorldIds.PILLAR).orElseThrow().state();
        long areaVersionBefore = version(WorldIds.AREA);

        ticker(ScriptedRandom.constant(0.0)).tick();

        List<JsonNode> es = entries();
        assertThat(kinds(es)).containsExactly("clock-anchored", "snapshot", "action-start");
        assertThat(es).extracting(e -> e.path("seq").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(es).allSatisfy(e -> {
            assertThat(e.path("area").asText()).isEqualTo(WorldIds.AREA);
            assertThat(e.path("time").asDouble()).isEqualTo(0.0);
            assertThat(Instant.parse(e.path("wallTime").asText())).isEqualTo(Instant.ofEpochMilli(T0));
        });
        long areaVersion = version(WorldIds.AREA);
        assertThat(areaVersion).isEqualTo(areaVersionBefore + 1);

        JsonNode anchor = es.get(0);
        assertThat(anchor.path("reason").asText()).isEqualTo("first-tick");
        assertThat(anchor.path("cause").isNull()).isTrue();
        assertThat(anchor.path("root").asLong()).isEqualTo(1);
        assertThat(anchor.path("resource").isNull()).isTrue();
        assertThat(anchor.path("action").isNull()).isTrue();
        assertThat(anchor.path("draws")).isEmpty();
        assertThat(anchor.path("changes")).hasSize(1);
        assertThat(versionOf(anchor, WorldIds.AREA)).isEqualTo(areaVersion);
        assertThat(anchor.path("changes").get(0).path("op").asText()).isEqualTo("changed");
        assertThat(anchor.path("changes").get(0).path("state")).isEqualTo(store.get(WorldIds.AREA).orElseThrow().state());

        JsonNode snap = es.get(1);
        assertThat(snap.path("cause").isNull()).isTrue();
        assertThat(snap.path("root").asLong()).isEqualTo(2);
        assertThat(snap.path("reason").isMissingNode()).isTrue();
        List<JsonNode> sc = new ArrayList<>();
        snap.path("changes").forEach(sc::add);
        assertThat(sc).extracting(c -> c.path("id").asText()).containsExactly(WorldIds.AREA, WorldIds.PILLAR, WorldIds.ORBITER);
        assertThat(sc).extracting(c -> c.path("op").asText()).containsOnly("snapshot");
        assertThat(sc.get(0).path("version").asLong()).isEqualTo(areaVersion); // the version this tick wrote
        assertThat(sc.get(0).path("state")).isEqualTo(store.get(WorldIds.AREA).orElseThrow().state());
        assertThat(sc.get(1).path("version").asLong()).isEqualTo(1);
        assertThat(sc.get(1).path("state")).isEqualTo(seededPillar);
        assertThat(sc.get(2).path("version").asLong()).isEqualTo(1); // as it was before the tick
        assertThat(sc.get(2).path("state")).isEqualTo(seededOrbiter);

        JsonNode start = es.get(2);
        assertThat(start.path("resource").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(start.path("cause").isNull()).isTrue();
        assertThat(start.path("root").asLong()).isEqualTo(3);
        assertThat(start.path("action").path("type").asText()).isEqualTo("orbit-step");
        assertThat(start.path("action").path("seq").asLong()).isEqualTo(3);
        assertThat(start.path("action").has("draw")).isFalse();
        assertThat(versionOf(start, WorldIds.ORBITER)).isEqualTo(version(WorldIds.ORBITER));
        JsonNode orbiter = store.get(WorldIds.ORBITER).orElseThrow().state();
        assertThat(orbiter.path("action").path("seq").asLong()).isEqualTo(3);
        assertThat(start.path("changes").get(0).path("state")).isEqualTo(orbiter);
    }

    @Test
    void aLateTickEndsAndRestartsAtTheExactEnd() throws Exception {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick();
        now.set(T0 + 1300); // game time 1.3: the step ended at 1.0
        t.tick();

        List<JsonNode> es = entriesAfter(3);
        assertThat(kinds(es)).containsExactly("action-end", "action-start");
        JsonNode end = es.get(0);
        JsonNode start = es.get(1);
        assertThat(end.path("seq").asLong()).isEqualTo(4);
        assertThat(end.path("cause").asLong()).isEqualTo(3);
        assertThat(end.path("root").asLong()).isEqualTo(3);
        assertThat(end.path("time").asDouble()).isEqualTo(1.0);
        assertThat(end.path("action").path("seq").asLong()).isEqualTo(3);
        assertThat(end.path("action").path("end").asDouble()).isEqualTo(1.0);
        assertThat(end.path("draws")).isEmpty();
        JsonNode endState = end.path("changes").get(0).path("state");
        assertThat(endState.has("action")).isFalse();
        assertThat(endState.path("position").path("x").asDouble()).isCloseTo(5.0 + 3.0 * Math.cos(ARC), Offset.offset(1e-9));
        assertThat(endState.path("position").path("y").asDouble()).isCloseTo(4.0 + 3.0 * Math.sin(ARC), Offset.offset(1e-9));

        assertThat(start.path("seq").asLong()).isEqualTo(5);
        assertThat(start.path("time").asDouble()).isEqualTo(1.0);
        assertThat(start.path("cause").isNull()).isTrue();
        assertThat(start.path("root").asLong()).isEqualTo(5);
        assertThat(start.path("action").path("seq").asLong()).isEqualTo(5);
        assertThat(start.path("action").path("start").asDouble()).isEqualTo(1.0);
        assertThat(start.path("changes").get(0).path("state").path("action").path("seq").asLong()).isEqualTo(5);

        long v = version(WorldIds.ORBITER);
        assertThat(versionOf(end, WorldIds.ORBITER)).isEqualTo(v);
        assertThat(versionOf(start, WorldIds.ORBITER)).isEqualTo(v);
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state().path("action").path("seq").asLong()).isEqualTo(5);
    }

    @Test
    void aRandomPickIsInTheStartEntryAndARepeatHasNoDraws() throws Exception {
        ticker(new ScriptedRandom(0.25)).tick();
        JsonNode start = entries().get(2);
        assertThat(start.path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"behaviour","dist":"categorical","weights":[2.0,1.0,1.0],"u":0.25,"value":0}]"""));
        // The state keeps its own draw record; the entry's action does not repeat it.
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state().path("action").path("draw").path("u").asDouble())
                .isEqualTo(0.25);
        assertThat(start.path("action").has("draw")).isFalse();
    }

    @Test
    void aRepeatBehaviourHasNoDraws() throws Exception {
        setOrbiter(REPEAT_ORBIT, null);
        ScriptedRandom g = new ScriptedRandom();
        ticker(g).tick();
        JsonNode start = entries().get(2);
        assertThat(start.path("kind").asText()).isEqualTo("action-start");
        assertThat(start.path("draws")).isEqualTo(JSON.createArrayNode());
        assertThat(g.draws()).isZero();
    }

    @Test
    void threeChainedActionsInOneTickMakeSixOrderedEntriesAndOneWrite() throws Exception {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick();
        long before = version(WorldIds.ORBITER);
        now.set(T0 + 3500); // ends at 1, 2 and 3 are due
        t.tick();
        List<JsonNode> es = entriesAfter(3);
        assertThat(kinds(es)).containsExactly("action-end", "action-start", "action-end", "action-start",
                "action-end", "action-start");
        assertThat(es).extracting(e -> e.path("time").asDouble()).containsExactly(1.0, 1.0, 2.0, 2.0, 3.0, 3.0);
        assertThat(es).extracting(e -> e.path("seq").asLong()).containsExactly(4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(before + 1);
        assertThat(es).allSatisfy(e -> assertThat(versionOf(e, WorldIds.ORBITER)).isEqualTo(before + 1));
        // Each end's cause is the start before it.
        assertThat(es.get(2).path("cause").asLong()).isEqualTo(5);
        assertThat(es.get(4).path("cause").asLong()).isEqualTo(7);
    }

    @Test
    void invariantsHoldOverManyTicks() throws Exception {
        Ticker t = ticker(new SplittableRandom(42));
        SplittableRandom steps = new SplittableRandom(7);
        Map<String, Set<Long>> written = new HashMap<>();
        Map<String, Long> last = new HashMap<>();
        for (String id : ALL) {
            written.put(id, new HashSet<>());
            last.put(id, version(id));
        }
        for (int i = 0; i < 250; i++) {
            now.addAndGet(200 + steps.nextInt(2000));
            t.tick();
            for (String id : ALL) {
                long v = version(id);
                if (v != last.get(id)) {
                    written.get(id).add(v);
                    last.put(id, v);
                }
            }
        }
        List<JsonNode> es = entries();
        assertThat(es.size()).isGreaterThan(250);
        Map<Long, JsonNode> bySeq = new HashMap<>();
        Map<String, Set<Long>> listed = new HashMap<>();
        double prevTime = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < es.size(); i++) {
            JsonNode e = es.get(i);
            assertThat(e.path("seq").asLong()).as("seq at index %d", i).isEqualTo(i + 1);
            assertThat(e.path("time").asDouble()).as("time of seq %d", i + 1).isGreaterThanOrEqualTo(prevTime);
            prevTime = e.path("time").asDouble();
            bySeq.put(e.path("seq").asLong(), e);
            for (JsonNode c : e.path("changes")) {
                if (c.path("op").asText().equals("changed")) {
                    listed.computeIfAbsent(c.path("id").asText(), k -> new HashSet<>()).add(c.path("version").asLong());
                }
            }
        }
        for (String id : ALL) {
            Set<Long> in = listed.getOrDefault(id, Set.of());
            if (!id.equals(WorldIds.AREA)) { // an area version that only changed the child list is covered by the child's created/destroyed
                assertThat(in).as("versions listed for %s", id).containsAll(written.get(id));
            }
            // and nothing is listed that was never written
            assertThat(written.get(id)).as("versions written for %s", id).containsAll(in);
        }
        assertThat(written.get(WorldIds.ORBITER)).hasSizeGreaterThan(100);
        for (JsonNode e : es) {
            if (e.path("kind").asText().equals("action-end")) {
                JsonNode cause = bySeq.get(e.path("cause").asLong());
                assertThat(cause).isNotNull();
                assertThat(cause.path("kind").asText()).isEqualTo("action-start");
                assertThat(cause.path("resource").asText()).isEqualTo(e.path("resource").asText());
                assertThat(cause.path("action").path("end").asDouble()).isEqualTo(e.path("time").asDouble());
                assertThat(e.path("root").asLong()).isEqualTo(cause.path("root").asLong());
            }
        }
    }

    @Test
    void replayingFromAnySnapshotReproducesTheCurrentState() throws Exception {
        Ticker t = ticker(new SplittableRandom(42));
        for (int i = 0; i < 40; i++) {
            now.addAndGet(700);
            t.tick();
        }
        now.addAndGet(Duration.ofMinutes(61).toMillis()); // pull-back and a periodic snapshot
        t.tick();
        for (int i = 0; i < 40; i++) {
            now.addAndGet(900);
            t.tick();
        }
        List<JsonNode> es = entries();
        List<Integer> snapshots = new ArrayList<>();
        for (int i = 0; i < es.size(); i++) {
            if (es.get(i).path("kind").asText().equals("snapshot")) {
                snapshots.add(i);
            }
        }
        assertThat(snapshots).hasSize(2);
        for (int from : snapshots) {
            Map<String, JsonNode> state = new HashMap<>();
            for (JsonNode e : es.subList(from, es.size())) {
                for (JsonNode c : e.path("changes")) {
                    state.put(c.path("id").asText(), c.path("state"));
                }
            }
            for (String id : ALL) {
                assertThat(state.get(id)).as("%s replayed from entry %d", id, from)
                        .isEqualTo(store.get(id).orElseThrow().state());
            }
        }
    }

    @Test
    void aPullBackIsAnEntryAndLaterTimesContinueFromIt() throws Exception {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick(); // seq 1..3
        now.set(T0 + 60_000); // far behind: the clock is pulled back to the due end (1.0)
        t.tick();
        List<JsonNode> es = entriesAfter(3);
        assertThat(kinds(es)).containsExactly("clock-anchored", "action-end", "action-start");
        JsonNode anchor = es.get(0);
        assertThat(anchor.path("reason").asText()).isEqualTo("pull-back");
        assertThat(anchor.path("time").asDouble()).isEqualTo(1.0);
        assertThat(anchor.path("cause").isNull()).isTrue();
        assertThat(anchor.path("changes").get(0).path("state").path("clock").path("gameTime").asDouble()).isEqualTo(1.0);
        assertThat(versionOf(anchor, WorldIds.AREA)).isEqualTo(version(WorldIds.AREA));
        assertThat(es.get(1).path("time").asDouble()).isEqualTo(1.0);
        assertThat(es.get(2).path("time").asDouble()).isEqualTo(1.0);

        now.addAndGet(1000);
        t.tick();
        List<JsonNode> later = entriesAfter(6);
        assertThat(later).extracting(e -> e.path("time").asDouble()).containsExactly(2.0, 2.0);
        assertThat(kinds(later)).containsExactly("action-end", "action-start");
    }

    @Test
    void aTickWithNothingDueWritesNothing() {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick();
        long last = jdbc.queryForObject("SELECT last_seq FROM area_log_head", Long.class);
        long orbiter = version(WorldIds.ORBITER);
        now.set(T0 + 500);
        t.tick();
        assertThat(entries()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT last_seq FROM area_log_head", Long.class)).isEqualTo(last);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiter);
    }

    @Test
    void aSnapshotOnlyTickWritesOneSnapshotAndNoResourceOrPublish() throws Exception {
        AtomicInteger published = new AtomicInteger();
        ChangePublisher counting = b -> {
            published.incrementAndGet();
            publisher.publish(b);
        };
        Ticker t = ticker(ScriptedRandom.constant(0.0), counting);
        t.tick();
        assertThat(published.get()).isEqualTo(1);
        // Stop game time, so the running action can never be due.
        ObjectNode area = store.get(WorldIds.AREA).orElseThrow().state().deepCopy();
        ((ObjectNode) area.get("clock")).put("rate", 0.0);
        store.updateState(WorldIds.AREA, area);
        long areaV = version(WorldIds.AREA);
        long orbiterV = version(WorldIds.ORBITER);
        long pillarV = version(WorldIds.PILLAR);
        JsonNode orbiterState = store.get(WorldIds.ORBITER).orElseThrow().state();

        now.set(T0 + Duration.ofMinutes(59).toMillis());
        t.tick();
        assertThat(entries()).hasSize(3);

        now.set(T0 + Duration.ofMinutes(61).toMillis());
        t.tick();
        List<JsonNode> es = entriesAfter(3);
        assertThat(kinds(es)).containsExactly("snapshot");
        assertThat(es.get(0).path("seq").asLong()).isEqualTo(4);
        assertThat(es.get(0).path("time").asDouble()).isEqualTo(0.0);
        assertThat(Instant.parse(es.get(0).path("wallTime").asText()))
                .isEqualTo(Instant.ofEpochMilli(T0 + Duration.ofMinutes(61).toMillis()));
        assertThat(versionOf(es.get(0), WorldIds.AREA)).isEqualTo(areaV);
        assertThat(versionOf(es.get(0), WorldIds.ORBITER)).isEqualTo(orbiterV);
        assertThat(versionOf(es.get(0), WorldIds.PILLAR)).isEqualTo(pillarV);
        assertThat(es.get(0).path("changes").get(2).path("state")).isEqualTo(orbiterState);
        assertThat(version(WorldIds.AREA)).isEqualTo(areaV);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiterV);
        assertThat(published.get()).isEqualTo(1);

        t.tick(); // the snapshot is fresh again
        assertThat(entries()).hasSize(4);
    }

    @Test
    void periodicSnapshotsPruneEntriesBeforeTheNewestOldOne() {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick(); // snapshot seq 2
        now.set(T0 + Duration.ofMinutes(61).toMillis());
        t.tick(); // pull-back, snapshot, end, start: nothing is older than 24 h yet
        List<JsonNode> es = entries();
        assertThat(es.get(0).path("seq").asLong()).isEqualTo(1);
        long secondSnapshot = es.stream().filter(e -> e.path("kind").asText().equals("snapshot"))
                .mapToLong(e -> e.path("seq").asLong()).max().orElseThrow();
        assertThat(secondSnapshot).isGreaterThan(2);

        now.set(T0 + Duration.ofHours(26).toMillis()); // both snapshots are older than 24 h now
        t.tick();
        JsonNode page = areaLog.read(WorldIds.AREA, 0, 500, null);
        long thirdSnapshot = 0;
        for (JsonNode e : page.path("entries")) {
            if (e.path("kind").asText().equals("snapshot")) {
                thirdSnapshot = e.path("seq").asLong();
                break;
            }
        }
        // The newest snapshot older than 24 h at that tick is the second one: it is the new start.
        assertThat(thirdSnapshot).isEqualTo(secondSnapshot);
        assertThat(page.path("first").asLong()).isEqualTo(secondSnapshot);
        long lastSeq = page.path("last").asLong();
        assertThat(page.path("entries").get(0).path("seq").asLong()).isEqualTo(secondSnapshot);

        now.addAndGet(1000);
        t.tick(); // seq keeps counting
        assertThat(areaLog.read(WorldIds.AREA, 0, 500, null).path("last").asLong()).isGreaterThan(lastSeq);
        assertThat(areaLog.read(WorldIds.AREA, 0, 500, null).path("first").asLong()).isEqualTo(secondSnapshot);
    }

    @Test
    void pruneRemovesExactlyTheEntriesBeforeTheNewestOldSnapshot() {
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick(); // 1 anchor, 2 snapshot, 3 start
        now.set(T0 + 1300);
        t.tick(); // 4 end, 5 start
        now.set(T0 + 2300);
        t.tick(); // 6 end, 7 start
        Instant nowI = Instant.ofEpochMilli(T0);
        // Nothing is old enough: nothing is deleted.
        assertThat(areaLog.prune(WorldIds.AREA, nowI)).isZero();
        jdbc.update("UPDATE area_log SET wall_time = ? WHERE seq <= 5", java.sql.Timestamp.from(nowI.minus(Duration.ofHours(30))));
        // The snapshot (seq 2) is the only snapshot: entries before it go, it stays.
        assertThat(areaLog.prune(WorldIds.AREA, nowI)).isEqualTo(1);
        JsonNode page = areaLog.read(WorldIds.AREA, 0, 500, null);
        assertThat(page.path("first").asLong()).isEqualTo(2);
        assertThat(page.path("last").asLong()).isEqualTo(7);
        assertThat(page.path("entries")).hasSize(6);
        assertThat(areaLog.prune(WorldIds.AREA, nowI)).isZero();
    }

    @Test
    void anActionWithoutSeqEndsWithoutACause() throws Exception {
        ObjectNode action = (ObjectNode) JSON.readTree("""
                {"type":"orbit-step","params":{"center":{"x":5.0,"y":4.0},"radius":3.0,"arc":0.5},
                 "start":0.0,"end":1.0,"effect":"end"}""");
        setOrbiter(REPEAT_ORBIT, action);
        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick();
        assertThat(kinds(entries())).containsExactly("clock-anchored", "snapshot"); // nothing due yet
        now.set(T0 + 1500);
        t.tick();
        List<JsonNode> es = entriesAfter(2);
        assertThat(kinds(es)).containsExactly("action-end", "action-start");
        JsonNode end = es.get(0);
        assertThat(end.path("seq").asLong()).isEqualTo(3);
        assertThat(end.path("cause").isNull()).isTrue();
        assertThat(end.path("root").asLong()).isEqualTo(3);
        assertThat(end.path("action").has("seq")).isFalse();
        assertThat(es.get(1).path("action").path("seq").asLong()).isEqualTo(4);
    }

    @Test
    void aFailedTickLeavesNoEntriesAndNoHeadChange() {
        ChangePublisher failing = batch -> {
            publisher.publish(batch);
            throw new IllegalStateException("boom");
        };
        // Fresh log: the failed first tick leaves nothing, and the next one starts at seq 1.
        assertThatThrownBy(ticker(ScriptedRandom.constant(0.0), failing)::tick).isInstanceOf(IllegalStateException.class);
        assertThat(entries()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM area_log_head WHERE last_seq <> 0", Integer.class)).isZero();

        Ticker t = ticker(ScriptedRandom.constant(0.0));
        t.tick();
        assertThat(entries()).hasSize(3);
        long head = jdbc.queryForObject("SELECT last_seq FROM area_log_head", Long.class);
        Instant snapAt = jdbc.queryForObject("SELECT last_snapshot_at FROM area_log_head", java.sql.Timestamp.class).toInstant();

        now.set(T0 + 1300); // a step is due
        assertThatThrownBy(ticker(ScriptedRandom.constant(0.0), failing)::tick).isInstanceOf(IllegalStateException.class);
        assertThat(entries()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT last_seq FROM area_log_head", Long.class)).isEqualTo(head);
        assertThat(jdbc.queryForObject("SELECT last_snapshot_at FROM area_log_head", java.sql.Timestamp.class).toInstant())
                .isEqualTo(snapAt);
    }
}
