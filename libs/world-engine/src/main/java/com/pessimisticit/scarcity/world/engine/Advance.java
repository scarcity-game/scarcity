package com.pessimisticit.scarcity.world.engine;

import tools.jackson.databind.node.ObjectNode;
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
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import com.pessimisticit.scarcity.world.model.rules.Progress;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private final AreaRun areaRun;
    private final Forecast forecast;
    private final Ledger ledger;
    private final RandomGenerator rng;
    private final Catalogue catalogue;
    private final long areaVersion;
    private final Deque<Work> work;
    private final Map<String, String> blocked;
    /** When each idle entity became idle: the snapshot's turns, then every action's end. */
    private final Map<String, Double> idleSince;
    private double now;
    /** Every channel has been moved on to this time (docs/actions.md, "Actions"): the area's time at the start. */
    private double progressedTo;
    private int steps;

    Advance(AreaSnapshot areaSnapshot, RandomGenerator rng, long nextSeq, Catalogue catalogue) {
        this.work = new ArrayDeque<>();
        this.blocked = new TreeMap<>();
        this.idleSince = new HashMap<>();
        this.areaRun = new AreaRun(areaSnapshot);
        this.ledger = new Ledger(nextSeq, areaSnapshot.roots());
        this.rng = rng;
        this.catalogue = catalogue;
        this.forecast = new Forecast(areaRun, (id, state) -> resume(id, state.actionInProgress())
                .motion(actor(id, state)));
        this.areaVersion = areaSnapshot.area().version();
        this.now = areaSnapshot.area().time();
        this.progressedTo = now;
        areaSnapshot.area().queue().forEach(t -> idleSince.put(t.entity(), t.since()));
    }

    AdvanceResult areaRun(Intent intent) {
        List<Turn> before = queue();
        BoundAction<?> bound = Admission.check(intent, areaRun, catalogue,
                before.isEmpty() ? null : before.getFirst().entity());
        long seq = ledger.allocate();
        ledger.add(LogEntry.intent(new LogEntry.Head(seq, now, null, seq), intent.entity(), intent.controller(),
                intent.actionSpec(), intent.reported()));
        start(intent.entity(), bound, null, new Work.Origin(now, seq, seq, intent.entity()));
        drain();
        Double stopAt = areaRun.needsDecision() ? now : null;
        return finish(stopAt, Double.POSITIVE_INFINITY);
    }

    /**
     * Runs an area that has to catch up to {@code until} with no intent (docs/actions.md, "Crossing
     * areas"): first, if the area is more than {@link Arrive#WARM_UP} behind, its time moves to
     * {@code until - WARM_UP} with nothing happening ({@code catch-up}); then events run up to
     * {@code until}, stopping when a controlled entity is idle: its service then takes its turn with
     * an intent, an ordinary advance, until the arrival ends at {@code until}. Empty when nothing
     * happened: the area is already at {@code until}.
     */
    Optional<AdvanceResult> warmUp(double until) {
        double target = until - Arrive.WARM_UP;
        if (now < target) {
            long seq = ledger.allocate();
            ledger.add(LogEntry.catchUp(new LogEntry.Head(seq, target, null, seq), now, target));
            now = target;
        }
        AdvanceResult result = finish(null, until);
        return result.log().isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private AdvanceResult finish(Double firstStop, double until) {
        Double stopAt = firstStop;
        for (Forecast.Event e = next(); e != null && e.time() <= until && (stopAt == null || e.time() <= stopAt); e = next()) {
            now = e.time();
            step();
            if (!progressAll(e.contact() ? null : e.entity())) {
                if (e.contact()) {
                    contact(e);
                } else {
                    process(e.entity());
                }
            }
            drain();
            stopAt = stopAt == null && decisionNeeded(until) ? Double.valueOf(now) : stopAt;
        }
        areaRun.moveTo(now);
        areaRun.setQueue(queue());
        return new AdvanceResult(areaRun.area().withVersion(areaVersion + 1), areaVersion + 1, areaRun.writes(),
                ledger.finish(areaRun::writtenVersion), areaRun.departures(), blocked);
    }

    /**
     * An advance needs a decision as soon as someone controlled is idle, no one is, or the area
     * is left empty. A warm-up stops only for someone idle: its arrival keeps it occupied, and it
     * ends at {@code until} anyway.
     */
    private boolean decisionNeeded(double until) {
        return until == Double.POSITIVE_INFINITY ? areaRun.needsDecision() : areaRun.anyIdle();
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
                ledger.finish(areaRun::writtenVersion), List.of(), blocked));
    }

    /**
     * The controlled idle entities, by {@code since} then id (docs/actions.md, "Turns"): the
     * first is up. One idle since before the snapshot keeps its {@code since}; one with no known
     * start (created idle) is queued from now. An area with no player, npc or arrival gives no turns.
     */
    private List<Turn> queue() {
        if (!areaRun.occupied()) {
            return List.of();
        }
        return areaRun.entities().entrySet().stream()
                .filter(e -> e.getValue().controller() != null && e.getValue().idle())
                .map(e -> new Turn(e.getKey(), e.getValue().controller(), idleSince.getOrDefault(e.getKey(), now)))
                .sorted(Comparator.comparingDouble(Turn::since).thenComparing(Turn::entity))
                .toList();
    }

    /** The earliest event, ties by id; null when none is left. */
    private Forecast.Event next() {
        Forecast.Event best = null;
        for (Map.Entry<String, Entity> e : areaRun.entities().entrySet()) {
            double due = dueAt(e.getKey(), e.getValue());
            if (due < Double.POSITIVE_INFINITY && (best == null || due < best.time())) {
                best = new Forecast.Event(due, e.getKey());
            }
        }
        Forecast.Event contact = forecast.next(progressedTo);
        return contact != null && contact.before(best) ? contact : best;
    }

    /**
     * A contact: the entry, then both movers are interrupted where they are, and each then picks
     * its next action (docs/actions.md, "Interruption"). An entity that is touched but is not
     * moving stays as it is.
     */
    private void contact(Forecast.Event event) {
        List<String> ids = new ArrayList<>(List.of(event.entity()));
        if (event.other() != null && areaRun.peek(event.other()).actionInProgress() != null
                && areaRun.peek(event.other()).actionInProgress().timing() == Timing.CHANNEL) {
            ids.add(event.other());
        }
        ids.sort(Comparator.naturalOrder());
        long seq = ledger.allocate();
        LogEntry.Head head = new LogEntry.Head(seq, now, null, seq);
        ledger.add(LogEntry.contact(head, event.id(), forecast.result(event)));
        Work.Origin cause = new Work.Origin(now, seq, seq, null);
        ids.forEach(id -> interrupt(id, cause, false));
        ids.stream().filter(id -> areaRun.peek(id).behaviour() != null).forEach(id -> startBehaviour(id, now));
    }

    /** An action's end; now for an idle entity whose behaviour can start; never otherwise. */
    private double dueAt(String id, Entity state) {
        if (state.actionInProgress() != null) {
            return state.actionInProgress().end();
        }
        boolean waiting = state.behaviour() != null && !blocked.containsKey(id);
        return waiting ? now : Double.POSITIVE_INFINITY;
    }

    /**
     * Before the first event at {@code now}, moves every channel on to it, in id order, so whatever
     * happens at {@code now} sees each mover where it has got to. {@code first}, the event about to
     * run, is skipped when it is that channel's own end: the end moves it. A channel that is stopped
     * ends here, at {@code now}; then true, and the caller picks the next event again.
     */
    private boolean progressAll(String first) {
        if (now <= progressedTo) {
            return false;
        }
        double from = progressedTo;
        progressedTo = now;
        boolean stopped = false;
        for (String id : List.copyOf(areaRun.entities().keySet())) {
            Entity state = areaRun.exists(id) ? areaRun.peek(id) : null;
            ActionInProgress action = state == null ? null : state.actionInProgress();
            boolean ownEnd = id.equals(first) && action != null && action.end() <= now;
            if (action != null && action.timing() == Timing.CHANNEL && !ownEnd) {
                stopped |= progress(id, state, action, now - Math.max(from, action.start()));
            }
        }
        return stopped;
    }

    /** Moves one channel on by {@code dt}; true when it was stopped and has ended. */
    private boolean progress(String id, Entity state, ActionInProgress action, double dt) {
        Progress progress = resume(id, action).progress(actor(id, state), dt);
        ActionInProgress after = progress.stopped() ? action.endingAt(now) : action;
        Entity moved = progress.entity().withAction(after);
        areaRun.put(id, moved);
        long seq = ledger.allocate();
        long root = action.seq() == null ? seq : ledger.rootOf(action.seq());
        ObjectNode result = progress.result() == null ? null : WorldJson.toObject(progress.result());
        ledger.add(LogEntry.actionProgress(new LogEntry.Head(seq, now, action.seq(), root), id, logged(after),
                result, moved));
        if (progress.stopped()) {
            end(id, moved);
        }
        return progress.stopped();
    }

    private void process(String id) {
        Entity state = areaRun.peek(id);
        if (state.idle()) {
            startBehaviour(id, now);
        } else {
            end(id, state);
        }
    }

    /** Ends the action: its change (an {@code end} timing, or the rest of a {@code channel}), the entry, its effects, then the behaviour's next start. */
    private void end(String id, Entity state) {
        ActionInProgress action = state.actionInProgress();
        BoundAction<?> bound = resume(id, action);
        long seq = ledger.allocate();
        long root = action.seq() == null ? seq : ledger.rootOf(action.seq());
        boolean changes = bound.timing() == Timing.END || bound.timing() == Timing.CHANNEL;
        Applied applied = changes ? bound.apply(actor(id, state)) : Applied.of(state);
        Entity after = settle(id, applied, action.end());
        LogEntry.Head head = new LogEntry.Head(seq, action.end(), action.seq(), root);
        ledger.add(LogEntry.actionEnd(head, id, logged(action), json(applied), after));
        schedule(bound.onEnd(actor(id, after)), new Work.Origin(action.end(), seq, root, id));
        drain();
        if (areaRun.exists(id) && areaRun.peek(id).idle() && areaRun.peek(id).behaviour() != null) {
            startBehaviour(id, action.end());
        }
    }

    /** Starts the behaviour's pick at {@code time}, or blocks the entity for this advance if it can't. */
    private void startBehaviour(String id, double time) {
        Pick pick = pick(id, areaRun.peek(id));
        if (pick != null) {
            start(id, pick.bound(), pick.draw(), new Work.Origin(time, -1, -1, id));
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
        schedule(bound.onStart(actor(id, applied.entity())), new Work.Origin(origin.time(), seq, root, id));
    }

    void startFromEffect(Work.StartAction s) {
        if (!areaRun.exists(s.entity())) {
            throw new IllegalStateException("cannot start " + s.actionSpec().type() + " on " + s.entity()
                    + ": it doesn't exist");
        }
        Entity state = areaRun.peek(s.entity());
        if (!state.idle()) {
            interrupt(s.entity(), s.origin(), false);
            state = areaRun.peek(s.entity());
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
        Work.Origin self = new Work.Origin(origin.time(), seq, origin.root(), origin.source());
        EffectRun ctx = new EffectRun(areaRun, rng, self);
        r.call().run(ctx);
        List<LogEntry.ActedUpon> actedUpon = actedUpon(ctx, origin.source());
        ledger.add(LogEntry.loggedEffect(new LogEntry.Head(seq, origin.time(), origin.cause(), origin.root()),
                new LoggedEffect(r.call().type(), r.call().paramsJson()), ctx.loggedResult(), ctx.draws(),
                ctx.changes(), actedUpon));
        for (EffectRun.Cancelled c : ctx.cancelled()) {
            ledger.add(LogEntry.cancelled(new LogEntry.Head(ledger.allocate(), origin.time(), seq, origin.root()),
                    c.entity(), logged(c.actionInProgress())));
        }
        actedUpon.stream().filter(LogEntry.ActedUpon::interrupted)
                .forEach(a -> interrupt(a.resource(), self, true));
        ctx.departures().forEach(areaRun::depart);
        pushAll(ctx.followUps());
    }

    /**
     * The resources the effect acted upon that have an action in progress, and whether it is
     * interrupted: not the {@code source}, whose own action set the chain off, and not one in
     * recovery (docs/actions.md, "Interruption").
     */
    private List<LogEntry.ActedUpon> actedUpon(EffectRun ctx, String source) {
        List<LogEntry.ActedUpon> acted = new ArrayList<>();
        for (String id : ctx.actedUpon()) {
            Entity state = areaRun.exists(id) ? areaRun.peek(id) : null;
            if (!id.equals(source) && state != null && state.actionInProgress() != null) {
                acted.add(new LogEntry.ActedUpon(id, resume(id, state.actionInProgress()).interruptible()));
            }
        }
        return acted;
    }

    /**
     * Ends the action of {@code id} now, at the cause's time (docs/actions.md, "Interruption"): the
     * action type's own change (none for most), an {@code action-interrupted} entry caused by the
     * cause, and no end effects. The behaviour, if it has one and {@code pickNext}, picks again at once.
     */
    private void interrupt(String id, Work.Origin cause, boolean pickNext) {
        Entity state = areaRun.peek(id);
        ActionInProgress action = state.actionInProgress();
        Applied applied = resume(id, action).interrupt(actor(id, state));
        Entity after = settle(id, applied, cause.time());
        LogEntry.Head head = new LogEntry.Head(ledger.allocate(), cause.time(), cause.cause(), cause.root());
        ledger.add(LogEntry.actionInterrupted(head, id, logged(action.endingAt(cause.time())), json(applied), after));
        if (pickNext && after.behaviour() != null) {
            startBehaviour(id, cause.time());
        }
    }

    /** Writes the entity as the action left it, idle from {@code time}; returns it. */
    private Entity settle(String id, Applied applied, double time) {
        Entity after = applied.entity().withAction(null);
        areaRun.put(id, after);
        idleSince.put(id, time);
        return after;
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
