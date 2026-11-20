package com.pessimisticit.scarcity.world.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One area log entry (docs/log.md). The fields the caller stores as columns
 * ({@code seq}, {@code time}, {@code kind}, {@code cause}, {@code root},
 * {@code resource}) are components; {@link #bodyJson()} is the rest, in the
 * contract's shape. The caller adds {@code area} and {@code wallTime}.
 *
 * <p>Components a kind doesn't carry are null: {@code by}, {@code intent} and
 * {@code reported} are the intent's, {@code action} the action entries'
 * (without its {@code draw}, which is in {@code draws}), {@code effect} the
 * effect's; {@code result} is an effect's or an action's, if it has one.
 */
public record LogEntry(
        long seq,
        double time,
        Kind kind,
        Long cause,
        long root,
        String resource,
        Controller controller,
        ActionSpec actionSpec,
        JsonNode reported,
        ActionInProgress actionInProgress,
        LoggedEffect loggedEffect,
        ObjectNode result,
        List<LoggedDraw> draws,
        List<LogChange> changes) {

    /** {@code kind}. */
    public enum Kind {
        SNAPSHOT("snapshot"), INTENT("intent"), EFFECT("effect"), ACTION_CANCELLED("action-cancelled"),
        ACTION_START("action-start"), ACTION_END("action-end"), QUEUE("queue"), CATCH_UP("catch-up");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Where an entry sits: its seq, game time, cause (null: none) and root. */
    record Head(long seq, double time, Long cause, long root) {
    }

    public LogEntry {
        Objects.requireNonNull(kind, "kind");
        reported = reported == null ? null : reported.deepCopy();
        result = result == null ? null : result.deepCopy();
        draws = List.copyOf(draws);
        changes = List.copyOf(changes);
    }

    @Override
    public JsonNode reported() {
        return reported == null ? null : reported.deepCopy();
    }

    @Override
    public ObjectNode result() {
        return result == null ? null : result.deepCopy();
    }

    /**
     * The {@code snapshot} entry the caller writes first when one is due
     * (docs/log.md, "Snapshots and pruning"): the area and every entity,
     * with their current versions. Give the engine {@code seq + 1} as its
     * next seq.
     */
    public static LogEntry snapshot(long seq, AreaSnapshot snapshot) {
        List<LogChange> changes = new ArrayList<>();
        changes.add(new LogChange(snapshot.area().id(), "area", LogChange.Op.SNAPSHOT,
                snapshot.area().version(), snapshot.area(), null));
        snapshot.entities().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(e -> changes.add(snapshotOf(e)));
        return new LogEntry(seq, snapshot.area().time(), Kind.SNAPSHOT, null, seq, null,
                null, null, null, null, null, null, List.of(), changes);
    }

    private static LogChange snapshotOf(Entity e) {
        return new LogChange(e.id(), "entity", LogChange.Op.SNAPSHOT, e.version(), e, null);
    }

    static LogEntry intent(Head h, String entity, Controller controller, ActionSpec actionSpec, JsonNode reported) {
        return new LogEntry(h.seq(), h.time(), Kind.INTENT, h.cause(), h.root(), entity,
                controller, actionSpec, reported, null, null, null, List.of(), List.of());
    }

    /** The queue written without an intent (docs/log.md, "queue"); it changes no entity. */
    static LogEntry queue(Head h) {
        return new LogEntry(h.seq(), h.time(), Kind.QUEUE, null, h.root(), null,
                null, null, null, null, null, null, List.of(), List.of());
    }

    /** The area's time moved from {@code from} to {@code to} with nothing happening (docs/log.md, "catch-up"). */
    static LogEntry catchUp(Head h, double from, double to) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("from", from);
        result.put("to", to);
        return new LogEntry(h.seq(), h.time(), Kind.CATCH_UP, null, h.root(), null,
                null, null, null, null, null, result, List.of(), List.of());
    }

    static LogEntry actionStart(Head h, String entity, ActionInProgress actionInProgress, List<LoggedDraw> draws,
            ObjectNode result, Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_START, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, draws, List.of(LogChange.changed(entity, state)));
    }

    static LogEntry actionEnd(Head h, String entity, ActionInProgress actionInProgress, ObjectNode result,
            Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_END, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, List.of(), List.of(LogChange.changed(entity, state)));
    }

    static LogEntry loggedEffect(Head h, LoggedEffect loggedEffect, ObjectNode result, List<LoggedDraw> draws,
            List<LogChange> changes) {
        return new LogEntry(h.seq(), h.time(), Kind.EFFECT, h.cause(), h.root(), null,
                null, null, null, null, loggedEffect, result, draws, changes);
    }

    static LogEntry cancelled(Head h, String entity, ActionInProgress actionInProgress) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_CANCELLED, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, null, List.of(), List.of());
    }

    /** This entry with every change that has no version yet given {@code version.apply(id)}. */
    LogEntry withVersions(Function<String, Long> version) {
        List<LogChange> resolved = changes.stream()
                .map(c -> c.version() == null && c.op() != LogChange.Op.DESTROYED
                        ? c.withVersion(version.apply(c.id())) : c)
                .toList();
        return new LogEntry(seq, time, kind, cause, root, resource, controller, actionSpec, reported, actionInProgress, loggedEffect,
                result, draws, resolved);
    }

    /** Everything but the columns, in the shape of docs/log.md. */
    public ObjectNode bodyJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        putRecord(node, "controller", controller);
        putRecord(node, "actionSpec", actionSpec);
        if (reported != null) {
            node.set("reported", reported.deepCopy());
        }
        putRecord(node, "actionInProgress", actionInProgress);
        putRecord(node, "loggedEffect", loggedEffect);
        if (result != null) {
            node.set("result", result.deepCopy());
        }
        ArrayNode drawsNode = node.putArray("draws");
        draws.forEach(d -> drawsNode.add(d.toJson()));
        ArrayNode changesNode = node.putArray("changes");
        changes.forEach(c -> changesNode.add(c.toJson()));
        return node;
    }

    private static void putRecord(ObjectNode node, String key, Object value) {
        if (value != null) {
            node.set(key, WorldJson.toObject(value));
        }
    }
}
