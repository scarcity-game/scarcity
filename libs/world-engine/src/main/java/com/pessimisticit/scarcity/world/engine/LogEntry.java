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
 * effect's; {@code result} is an effect's or an action's, if it has one;
 * {@code generation} is on the first {@code snapshot} of an area that
 * generation made: its {@code tessellation} and {@code generator}.
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
        List<LogChange> changes,
        List<ActedUpon> actedUpon,
        ObjectNode generation) {

    /** A resource an effect acted upon that had an action in progress: whether the action was interrupted (not, in recovery). */
    public record ActedUpon(String resource, boolean interrupted) {
    }

    /** {@code kind}. */
    public enum Kind {
        SNAPSHOT("snapshot"), INTENT("intent"), EFFECT("effect"), ACTION_CANCELLED("action-cancelled"),
        ACTION_START("action-start"), ACTION_PROGRESS("action-progress"), ACTION_END("action-end"),
        ACTION_INTERRUPTED("action-interrupted"), CONTACT("contact"), QUEUE("queue"), CATCH_UP("catch-up");

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
        generation = generation == null ? null : generation.deepCopy();
        draws = List.copyOf(draws);
        changes = List.copyOf(changes);
        actedUpon = List.copyOf(actedUpon);
    }

    @Override
    public JsonNode reported() {
        return reported == null ? null : reported.deepCopy();
    }

    @Override
    public ObjectNode result() {
        return result == null ? null : result.deepCopy();
    }

    @Override
    public ObjectNode generation() {
        return generation == null ? null : generation.deepCopy();
    }

    /**
     * The {@code snapshot} entry the caller writes first when one is due
     * (docs/log.md, "Snapshots and pruning"): the area and every entity,
     * with their current versions. Give the engine {@code seq + 1} as its
     * next seq.
     */
    public static LogEntry snapshot(long seq, AreaSnapshot snapshot) {
        return snapshot(seq, snapshot, null, List.of());
    }

    /**
     * The first {@code snapshot} of an area that generation made (docs/log.md): with its
     * {@code generation} ({@code tessellation} and {@code generator}) and every draw that made it.
     */
    public static LogEntry snapshot(long seq, AreaSnapshot snapshot, ObjectNode generation, List<LoggedDraw> draws) {
        List<LogChange> changes = new ArrayList<>();
        changes.add(new LogChange(snapshot.area().id(), "area", LogChange.Op.SNAPSHOT,
                snapshot.area().version(), snapshot.area(), null));
        snapshot.entities().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(e -> changes.add(snapshotOf(e)));
        return new LogEntry(seq, snapshot.area().time(), Kind.SNAPSHOT, null, seq, null,
                null, null, null, null, null, null, draws, changes, List.of(), generation);
    }

    private static LogChange snapshotOf(Entity e) {
        return new LogChange(e.id(), "entity", LogChange.Op.SNAPSHOT, e.version(), e, null);
    }

    static LogEntry intent(Head h, String entity, Controller controller, ActionSpec actionSpec, JsonNode reported) {
        return new LogEntry(h.seq(), h.time(), Kind.INTENT, h.cause(), h.root(), entity,
                controller, actionSpec, reported, null, null, null, List.of(), List.of(), List.of(), null);
    }

    /** The queue written without an intent (docs/log.md, "queue"); it changes no entity. */
    static LogEntry queue(Head h) {
        return new LogEntry(h.seq(), h.time(), Kind.QUEUE, null, h.root(), null,
                null, null, null, null, null, null, List.of(), List.of(), List.of(), null);
    }

    /** The area's time moved from {@code from} to {@code to} with nothing happening (docs/log.md, "catch-up"). */
    static LogEntry catchUp(Head h, double from, double to) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("from", from);
        result.put("to", to);
        return new LogEntry(h.seq(), h.time(), Kind.CATCH_UP, null, h.root(), null,
                null, null, null, null, null, result, List.of(), List.of(), List.of(), null);
    }

    static LogEntry actionStart(Head h, String entity, ActionInProgress actionInProgress, List<LoggedDraw> draws,
            ObjectNode result, Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_START, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, draws, List.of(LogChange.changed(entity, state)), List.of(), null);
    }

    /** A channel moved on to {@code h.time()} (docs/log.md, "action-progress"). */
    static LogEntry actionProgress(Head h, String entity, ActionInProgress actionInProgress, ObjectNode result,
            Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_PROGRESS, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, List.of(), List.of(LogChange.changed(entity, state)), List.of(), null);
    }

    static LogEntry actionEnd(Head h, String entity, ActionInProgress actionInProgress, ObjectNode result,
            Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_END, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, List.of(), List.of(LogChange.changed(entity, state)), List.of(), null);
    }

    static LogEntry loggedEffect(Head h, LoggedEffect loggedEffect, ObjectNode result, List<LoggedDraw> draws,
            List<LogChange> changes, List<ActedUpon> actedUpon) {
        return new LogEntry(h.seq(), h.time(), Kind.EFFECT, h.cause(), h.root(), null,
                null, null, null, null, loggedEffect, result, draws, changes, actedUpon, null);
    }
    static LogEntry contact(Head h, String entity, ObjectNode result) {
        return new LogEntry(h.seq(), h.time(), Kind.CONTACT, h.cause(), h.root(), entity,
                null, null, null, null, null, result, List.of(), List.of(), List.of(), null);
    }

    static LogEntry actionInterrupted(Head h, String entity, ActionInProgress actionInProgress, ObjectNode result,
            Entity state) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_INTERRUPTED, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, result, List.of(), List.of(LogChange.changed(entity, state)), List.of(), null);
    }

    static LogEntry cancelled(Head h, String entity, ActionInProgress actionInProgress) {
        return new LogEntry(h.seq(), h.time(), Kind.ACTION_CANCELLED, h.cause(), h.root(), entity,
                null, null, null, actionInProgress, null, null, List.of(), List.of(), List.of(), null);
    }

    /** This entry with every change that has no version yet given {@code version.apply(id)}. */
    LogEntry withVersions(Function<String, Long> version) {
        List<LogChange> resolved = changes.stream()
                .map(c -> c.version() == null && c.op() != LogChange.Op.DESTROYED
                        ? c.withVersion(version.apply(c.id())) : c)
                .toList();
        return new LogEntry(seq, time, kind, cause, root, resource, controller, actionSpec, reported, actionInProgress, loggedEffect,
                result, draws, resolved, actedUpon, null);
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
        if (generation != null) {
            node.set("generation", generation.deepCopy());
        }
        ArrayNode drawsNode = node.putArray("draws");
        draws.forEach(d -> drawsNode.add(d.toJson()));
        ArrayNode changesNode = node.putArray("changes");
        changes.forEach(c -> changesNode.add(c.toJson()));
        if (!actedUpon.isEmpty()) {
            ArrayNode actedNode = node.putArray("actedUpon");
            actedUpon.forEach(a -> actedNode.add(WorldJson.toObject(a)));
        }
        return node;
    }

    private static void putRecord(ObjectNode node, String key, Object value) {
        if (value != null) {
            node.set(key, WorldJson.toObject(value));
        }
    }
}
