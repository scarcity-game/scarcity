package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Actor;
import com.pessimisticit.scarcity.world.model.rules.Applied;
import com.pessimisticit.scarcity.world.model.rules.BoundAction;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * One advance (docs/actions.md, "Game time"): start the intent's action,
 * then process events in order of time, then id, until a controlled entity
 * is idle, finishing every event at that time, and write whose turn it is
 * ({@code activeEntity}, {@code pending}) into the area. Only the active entity
 * may act. Every rule is a call on an
 * interface the catalogue returned; nothing here knows a type's name.
 */
final class Advance {

    /** An entity's next event. */
    private record Event(double time, String entity) {
    }

    private final AreaRun areaRun;
    private final Ledger ledger;
    private final RandomGenerator rng;
    private final Catalogue catalogue;
    private final long areaVersion;
    private final Deque<Work> work;
    private final Map<String, String> blocked;
    /** When each idle entity became idle: the snapshot's turns, then every action's end. */
    private final Map<String, Double> idleSince;
    private double now;
    private int steps;

    Advance(AreaSnapshot areaSnapshot, RandomGenerator rng, long nextSeq, Catalogue catalogue) {
        this.work = new ArrayDeque<>();
        this.blocked = new TreeMap<>();
        this.idleSince = new HashMap<>();
        this.areaRun = new AreaRun(areaSnapshot);
        this.ledger = new Ledger(nextSeq, areaSnapshot.roots());
        this.rng = rng;
        this.catalogue = catalogue;
        this.areaVersion = areaSnapshot.area().version();
        this.now = areaSnapshot.area().time();
        areaSnapshot.area().queue().forEach(t -> idleSince.put(t.entity(), t.since()));
    }

    AdvanceResult areaRun(Intent intent) {
        List<Turn> before = queue();
        BoundAction<?> bound = Admission.check(intent, areaRun, catalogue,
                before.isEmpty() ? null : before.getFirst().entity());
        long seq = ledger.allocate();
        ledger.add(LogEntry.intent(new LogEntry.Head(seq, now, null, seq), intent.entity(), intent.controller(),
                intent.actionSpec(), intent.reported()));
        start(intent.entity(), bound, null, new Work.Origin(now, seq, seq));
        drain();
        Double stopAt = areaRun.needsDecision() ? now : null;
        for (Event e = next(); e != null && (stopAt == null || e.time() <= stopAt); e = next()) {
            now = e.time();
            step();
            process(e.entity());
            drain();
            stopAt = stopAt == null && areaRun.needsDecision() ? Double.valueOf(now) : stopAt;
        }
        areaRun.moveTo(now);
        areaRun.setQueue(queue());
        return new AdvanceResult(areaRun.area().withVersion(areaVersion + 1), areaVersion + 1, areaRun.writes(),
                ledger.finish(areaRun::writtenVersion), blocked);
    }

    /**
     * The queue (docs/actions.md, "Turns") written into an area whose stored one is not what its
     * entities give: nothing starts and no time passes. Empty when the stored queue is right.
     */
    Optional<AdvanceResult> refresh() {
        List<Turn> queue = queue();
        if (queue.equals(areaRun.area().queue())) {
            return Optional.empty();
        }
        long seq = ledger.allocate();
        ledger.add(LogEntry.queue(new LogEntry.Head(seq, now, null, seq)));
        areaRun.setQueue(queue);
        return Optional.of(new AdvanceResult(areaRun.area().withVersion(areaVersion + 1), areaVersion + 1, areaRun.writes(),
                ledger.finish(areaRun::writtenVersion), blocked));
    }

    /**
     * The controlled idle entities, by {@code since} then id (docs/actions.md, "Turns"): the
     * first is up. One idle since before the snapshot keeps its {@code since}; one with no known
     * start (created idle) is queued from now.
     */
    private List<Turn> queue() {
        return areaRun.entities().entrySet().stream()
                .filter(e -> e.getValue().controller() != null && e.getValue().idle())
                .map(e -> new Turn(e.getKey(), e.getValue().controller(), idleSince.getOrDefault(e.getKey(), now)))
                .sorted(Comparator.comparingDouble(Turn::since).thenComparing(Turn::entity))
                .toList();
    }

    /** The earliest event, ties by id; null when none is left. */
    private Event next() {
        Event best = null;
        for (Map.Entry<String, Entity> e : areaRun.entities().entrySet()) {
            double due = dueAt(e.getKey(), e.getValue());
            if (due < Double.POSITIVE_INFINITY && (best == null || due < best.time())) {
                best = new Event(due, e.getKey());
            }
        }
        return best;
    }

    /** An action's end; now for an idle entity whose behaviour can start; never otherwise. */
    private double dueAt(String id, Entity state) {
        if (state.actionInProgress() != null) {
            return state.actionInProgress().end();
        }
        boolean waiting = state.behaviour() != null && !blocked.containsKey(id);
        return waiting ? now : Double.POSITIVE_INFINITY;
    }

    private void process(String id) {
        Entity state = areaRun.peek(id);
        if (state.idle()) {
            startBehaviour(id, now);
        } else {
            end(id, state);
        }
    }

    /** Ends the action: its change (an {@code end} timing), the entry, its effects, then the behaviour's next start. */
    private void end(String id, Entity state) {
        ActionInProgress action = state.actionInProgress();
        BoundAction<?> bound = resume(id, action);
        long seq = ledger.allocate();
        long root = action.seq() == null ? seq : ledger.rootOf(action.seq());
        Applied applied = bound.timing() == Timing.END ? bound.apply(actor(id, state)) : Applied.of(state);
        Entity after = applied.entity().withAction(null);
        areaRun.put(id, after);
        idleSince.put(id, action.end());
        ledger.add(LogEntry.actionEnd(new LogEntry.Head(seq, action.end(), action.seq(), root), id,
                logged(action), json(applied), after));
        schedule(bound.onEnd(actor(id, after)), new Work.Origin(action.end(), seq, root));
        drain();
        if (areaRun.exists(id) && areaRun.peek(id).idle() && areaRun.peek(id).behaviour() != null) {
            startBehaviour(id, action.end());
        }
    }

    /** Starts the behaviour's pick at {@code time}, or blocks the entity for this advance if it can't. */
    private void startBehaviour(String id, double time) {
        Pick pick = pick(id, areaRun.peek(id));
        if (pick != null) {
            start(id, pick.bound(), pick.draw(), new Work.Origin(time, -1, -1));
        }
    }

    private record Pick(BoundAction<?> bound, Draw draw) {
    }

    /**
     * Every choice's type must be listed before the behaviour draws
     * (docs/action-types.md, "Behaviours"); the pick must then pass every start
     * rule. A pick that doesn't starts nothing, and its draw isn't logged.
     */
    private Pick pick(String id, Entity state) {
        Actor self = actor(id, state);
        try {
            state.behaviour().options().forEach(option -> catalogue.listed(option, state));
            var chosen = state.behaviour().next(rng);
            return new Pick(catalogue.start(chosen.actionSpec(), self), chosen.draw());
        } catch (InvalidAction e) {
            blocked.put(id, e.getMessage());
            return null;
        }
    }

    /**
     * Starts {@code bound} on an idle entity: the action in its state (and
     * its change, for a {@code start} timing), the entry, then its effects.
     * A negative {@code origin.cause} is a behaviour's start: no cause, its own root.
     */
    private void start(String id, BoundAction<?> bound, Draw draw, Work.Origin origin) {
        long seq = ledger.allocate();
        boolean own = origin.cause() < 0;
        long root = own ? seq : origin.root();
        Entity state = areaRun.peek(id);
        ActionInProgress action = bound.inProgress(state, origin.time(), seq, draw);
        Entity started = state.withAction(action);
        idleSince.remove(id);
        Applied applied = bound.timing() == Timing.START ? bound.apply(actor(id, started)) : Applied.of(started);
        areaRun.put(id, applied.entity());
        List<LoggedDraw> draws = draw == null ? List.of() : List.of(new LoggedDraw("behaviour", draw));
        ledger.add(LogEntry.actionStart(new LogEntry.Head(seq, origin.time(), own ? null : origin.cause(), root),
                id, logged(action), draws, json(applied), applied.entity()));
        schedule(bound.onStart(actor(id, applied.entity())), new Work.Origin(origin.time(), seq, root));
    }

    void startFromEffect(Work.StartAction s) {
        if (!areaRun.exists(s.entity())) {
            throw new IllegalStateException("cannot start " + s.actionSpec().type() + " on " + s.entity()
                    + ": it doesn't exist");
        }
        Entity state = areaRun.peek(s.entity());
        if (!state.idle()) {
            throw new IllegalStateException("cannot start " + s.actionSpec().type() + " on " + s.entity()
                    + ": it already has an action");
        }
        BoundAction<?> bound;
        try {
            bound = catalogue.start(s.actionSpec(), actor(s.entity(), state));
        } catch (InvalidAction e) {
            throw new IllegalStateException("an effect can't start " + e.getMessage() + " on " + s.entity(), e);
        }
        start(s.entity(), bound, null, s.origin());
    }

    void runEffect(Work.RunEffect r) {
        Work.Origin origin = r.origin();
        long seq = ledger.allocate();
        Work.Origin self = new Work.Origin(origin.time(), seq, origin.root());
        EffectRun ctx = new EffectRun(areaRun, rng, self);
        r.call().run(ctx);
        ledger.add(LogEntry.loggedEffect(new LogEntry.Head(seq, origin.time(), origin.cause(), origin.root()),
                new LoggedEffect(r.call().type(), r.call().paramsJson()), ctx.loggedResult(), ctx.draws(),
                ctx.changes()));
        for (EffectRun.Cancelled c : ctx.cancelled()) {
            ledger.add(LogEntry.cancelled(new LogEntry.Head(ledger.allocate(), origin.time(), seq, origin.root()),
                    c.entity(), logged(c.actionInProgress())));
        }
        pushAll(ctx.followUps());
    }

    private void schedule(List<EffectCall<?>> calls, Work.Origin origin) {
        pushAll(calls.stream().<Work>map(c -> new Work.RunEffect(c, origin)).toList());
    }

    /** Pushes {@code items} so that they run in their order, before anything pushed earlier. */
    private void pushAll(List<Work> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            work.push(items.get(i));
        }
    }

    private void drain() {
        while (!work.isEmpty()) {
            step();
            work.pop().runOn(this);
        }
    }

    private void step() {
        steps++;
        if (steps > Engine.MAX_STEPS) {
            throw new RunawayAdvance("the advance of " + areaRun.areaId() + " processed more than "
                    + Engine.MAX_STEPS + " events and effects; a rule never stops");
        }
    }

    private BoundAction<?> resume(String id, ActionInProgress action) {
        try {
            return catalogue.resume(action);
        } catch (InvalidAction e) {
            throw new IllegalStateException(id + ": its action can't be ended: " + e.getMessage(), e);
        }
    }

    private Actor actor(String id, Entity state) {
        return new Actor(id, state, areaRun);
    }

    /** The action as an entry carries it: its draw is in the entry's {@code draws}. */
    private static ActionInProgress logged(ActionInProgress a) {
        return new ActionInProgress(a.type(), a.params(), a.start(), a.end(), a.timing(), a.seq(), null);
    }

    private static ObjectNode json(Applied applied) {
        return applied.result() == null ? null : WorldJson.toObject(applied.result());
    }
}
