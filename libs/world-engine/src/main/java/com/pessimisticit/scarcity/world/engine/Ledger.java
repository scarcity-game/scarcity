package com.pessimisticit.scarcity.world.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The entries of one advance, in seq order with no gaps, and the root of every seq it knows. */
final class Ledger {

    private final List<LogEntry> entries;
    private final Map<Long, Long> roots;
    private long next;
    private long added;

    Ledger(long nextSeq, Map<Long, Long> roots) {
        this.entries = new ArrayList<>();
        if (nextSeq < 1) {
            throw new IllegalArgumentException("nextSeq: must be >= 1, got " + nextSeq);
        }
        this.next = nextSeq;
        this.added = nextSeq - 1;
        this.roots = new HashMap<>(roots);
    }

    /** The seq of the next entry. Every seq allocated must be added, in order. */
    long allocate() {
        return next++;
    }

    void add(LogEntry entry) {
        if (entry.seq() != added + 1) {
            throw new IllegalStateException("log entry " + entry.seq() + " out of order; expected " + (added + 1));
        }
        added = entry.seq();
        entries.add(entry);
        roots.put(entry.seq(), entry.root());
    }

    /** The root of the entry {@code seq}; a seq the log no longer has is its own root. */
    long rootOf(long seq) {
        return roots.getOrDefault(seq, seq);
    }

    List<LogEntry> finish(Function<String, Long> version) {
        return entries.stream().map(e -> e.withVersions(version)).toList();
    }
}
