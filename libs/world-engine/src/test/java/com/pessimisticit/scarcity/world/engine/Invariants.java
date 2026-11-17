package com.pessimisticit.scarcity.world.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The rules of docs/log.md and docs/actions.md every advance must keep. */
final class Invariants {

    private Invariants() {
    }

    static void check(AreaSnapshot before, AdvanceResult r, long nextSeq) {
        check(before, r, nextSeq, true);
    }

    /** A warm-up has no intent entry, and its first entry may be after the area's time (a catch-up). */
    static void checkWarmUp(AreaSnapshot before, AdvanceResult r, long nextSeq) {
        check(before, r, nextSeq, false);
    }

    private static void check(AreaSnapshot before, AdvanceResult r, long nextSeq, boolean intent) {
        List<LogEntry> log = r.log();
        assertThat(log).isNotEmpty();
        assertThat(log.stream().filter(e -> e.kind() == LogEntry.Kind.INTENT)).hasSize(intent ? 1 : 0);
        if (intent) {
            assertThat(log.getFirst().kind()).isEqualTo(LogEntry.Kind.INTENT);
            assertThat(log.getFirst().time()).isEqualTo(before.area().time());
        }
        assertThat(log.getLast().time()).as("area time is the last entry's").isEqualTo(r.area().time());
        assertThat(r.areaVersion()).isEqualTo(before.area().version() + 1);
        Map<Long, Long> rootBySeq = new HashMap<>();
        for (int i = 0; i < log.size(); i++) {
            LogEntry e = log.get(i);
            assertThat(e.seq()).as("gapless seq").isEqualTo(nextSeq + i);
            if (i > 0) {
                assertThat(e.time()).as("time never decreases").isGreaterThanOrEqualTo(log.get(i - 1).time());
            }
            assertThat(e.root()).as("root of " + e.seq()).isEqualTo(expectedRoot(e, rootBySeq, before));
            rootBySeq.put(e.seq(), e.root());
        }
        checkWrites(before, r);
    }

    private static long expectedRoot(LogEntry e, Map<Long, Long> rootBySeq, AreaSnapshot before) {
        if (e.cause() == null) {
            return e.seq();
        }
        assertThat(e.cause()).as("cause before " + e.seq()).isLessThan(e.seq());
        Long inAdvance = rootBySeq.get(e.cause());
        return inAdvance != null ? inAdvance : before.roots().getOrDefault(e.cause(), e.cause());
    }

    /** One write per entity, each covered by an entry with its version, and every logged version is a written one. */
    private static void checkWrites(AreaSnapshot before, AdvanceResult r) {
        Set<String> existing = new HashSet<>();
        before.entities().forEach(e -> existing.add(e.id()));
        Map<String, Write> byId = new HashMap<>();
        for (Write w : r.writes()) {
            assertThat(byId.put(w.id(), w)).as("one write of " + w.id()).isNull();
            assertThat(existing.contains(w.id())).as(w.id() + " existed").isEqualTo(w.op() != Write.Op.CREATED);
        }
        List<LogChange> changes = r.log().stream().flatMap(e -> e.changes().stream()).toList();
        for (LogChange c : changes) {
            Write w = byId.get(c.id());
            Long expected = w == null || c.op() == LogChange.Op.DESTROYED ? null : w.version();
            assertThat(c.version()).as("logged version of " + c.id()).isEqualTo(expected);
        }
        for (Write w : r.writes()) {
            assertThat(changes).as("an entry covers " + w.id())
                    .anyMatch(c -> c.id().equals(w.id()) && java.util.Objects.equals(c.version(), w.version()));
        }
    }

    /**
     * Nothing due at or before the area's time is left, and the game is waiting for a decision, or
     * no player, npc or arrival is left and the area stands still.
     */
    static void afterApply(Area area, Collection<Entity> entities) {
        double time = area.time();
        for (Entity e : entities) {
            if (e.actionInProgress() != null) {
                assertThat(e.actionInProgress().end()).as(e.id() + " pending").isGreaterThan(time);
            }
        }
        List<Entity> controlled = entities.stream()
                .filter(s -> s.controller() != null).toList();
        boolean occupied = entities.stream().anyMatch(Entity::occupies);
        assertThat(!occupied || controlled.isEmpty() || controlled.stream().anyMatch(Entity::idle))
                .as("a controlled entity is idle").isTrue();
    }
}
